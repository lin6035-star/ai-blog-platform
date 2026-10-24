/**
 * Public read API baseline benchmark + same-JVM A/B logger-level comparison.
 *
 * Closed-loop concurrency model: each worker sends the next request only
 * after the previous request completes. Throughput is completed requests/sec,
 * not a target arrival rate.
 *
 * Examples:
 *   node deploy/api-stress/read-baseline.mjs --levels 1,10,30 --duration 15
 *   node deploy/api-stress/read-baseline.mjs --only article-search --levels 50 --duration 60 --warmup 60
 *
 * A/B mode: keeps one JVM alive and flips a single logger level through the
 * actuator `loggers` endpoint between rounds, so process start, JIT state and
 * cache warmth are identical for both variants. Requires the backend to be
 * started with `--management.endpoints.web.exposure.include=health,loggers`.
 *
 *   node deploy/api-stress/read-baseline.mjs --only article-search --levels 50 \
 *     --duration 60 --warmup 60 \
 *     --ab-logger com.hailin.blogsystem.search.ArticleSearchService \
 *     --ab-levels DEBUG,INFO --ab-rounds 5
 *
 * The script intentionally checks both HTTP status and the project's business
 * response code. Many application errors use HTTP 200 with a non-zero code.
 */
import { mkdir, writeFile } from 'node:fs/promises'
import { readFileSync, statSync } from 'node:fs'
import { createHash } from 'node:crypto'
import { execSync } from 'node:child_process'
import os from 'node:os'
import { performance } from 'node:perf_hooks'

const args = process.argv.slice(2)
const valueOf = (flag, fallback) => {
  const index = args.indexOf(flag)
  return index >= 0 && args[index + 1] !== undefined ? args[index + 1] : fallback
}
const numberOf = (flag, fallback) => {
  const value = Number(valueOf(flag, fallback))
  if (!Number.isFinite(value) || value <= 0) throw new Error(`invalid ${flag}: ${value}`)
  return value
}

const base = String(valueOf('--base', 'http://127.0.0.1:8080')).replace(/\/$/, '')
const durationSec = numberOf('--duration', 15)
const warmupSec = numberOf('--warmup', 5)
const timeoutMs = numberOf('--timeout-ms', 10_000)
const levels = String(valueOf('--levels', '1,10,30'))
  .split(',')
  .map((value) => Number(value.trim()))
  .filter((value) => Number.isInteger(value) && value > 0)
if (!levels.length) throw new Error('no valid concurrency levels')

const articleId = String(valueOf('--article-id', '2085208555163787287'))
const only = valueOf('--only', null)

const abLogger = valueOf('--ab-logger', null)
const abLevels = String(valueOf('--ab-levels', 'DEBUG,INFO'))
  .split(',')
  .map((value) => value.trim().toUpperCase())
  .filter(Boolean)
const abRounds = numberOf('--ab-rounds', 5)
const fingerprintPath = String(
  valueOf(
    '--fingerprint',
    'blog-system/src/main/java/com/hailin/blogsystem/search/ArticleSearchService.java',
  ),
)

// expect:
//   business  -> HTTP 200 and body.code === 0 (project convention)
//   http200   -> HTTP 200 only (health endpoint has no business code)
//   transport -> any HTTP response counts (used to measure the client ceiling)
const scenarios = [
  { name: 'article-list', path: '/api/articles?pageSize=10', expect: 'business' },
  { name: 'article-detail', path: `/api/articles/${articleId}`, expect: 'business' },
  { name: 'article-hot', path: '/api/articles/hot', expect: 'business' },
  { name: 'article-comments', path: `/api/articles/${articleId}/comments?page=1&pageSize=10`, expect: 'business' },
  { name: 'user-search', path: '/api/users/search?keyword=012&page=1&pageSize=10', expect: 'business' },
  { name: 'article-search', path: '/api/articles?keyword=Redis&pageSize=10', expect: 'business' },
  { name: 'health', path: '/actuator/health', expect: 'http200' },
  { name: 'probe-not-found', path: '/api/__probe__', expect: 'transport' },
]
const selected = only ? scenarios.filter((scenario) => scenario.name === only) : scenarios
if (!selected.length) throw new Error(`unknown --only value: ${only}`)

const percentile = (values, p) => {
  if (!values.length) return null
  const sorted = [...values].sort((a, b) => a - b)
  const index = Math.min(sorted.length - 1, Math.ceil((p / 100) * sorted.length) - 1)
  return sorted[index]
}

const median = (values) => {
  if (!values.length) return null
  const sorted = [...values].sort((a, b) => a - b)
  const mid = Math.floor(sorted.length / 2)
  return sorted.length % 2 ? sorted[mid] : Math.round((sorted[mid - 1] + sorted[mid]) / 2)
}

async function request(scenario) {
  const started = performance.now()
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), timeoutMs)
  const result = { latencyMs: 0, httpStatus: null, businessCode: null, ok: false, error: null }
  try {
    const response = await fetch(`${base}${scenario.path}`, {
      headers: { Accept: 'application/json' },
      signal: controller.signal,
    })
    result.httpStatus = response.status
    const text = await response.text()
    try {
      result.businessCode = JSON.parse(text)?.code ?? null
    } catch {
      result.error = 'invalid-json'
    }
    if (scenario.expect === 'transport') {
      result.ok = result.httpStatus !== null
    } else if (scenario.expect === 'http200') {
      result.ok = result.httpStatus === 200
    } else {
      result.ok = result.httpStatus === 200 && result.businessCode === 0
    }
  } catch (error) {
    result.error = error?.name === 'AbortError' ? `timeout-${timeoutMs}ms` : String(error?.message ?? error)
  } finally {
    clearTimeout(timer)
    result.latencyMs = Math.round(performance.now() - started)
  }
  return result
}

async function runPhase(scenario, concurrency, seconds, collect) {
  const endAt = Date.now() + seconds * 1000
  const worker = async () => {
    while (Date.now() < endAt) {
      const result = await request(scenario)
      if (collect) collect.push(result)
    }
  }
  await Promise.all(Array.from({ length: concurrency }, worker))
}

function summarize(results, durationSeconds) {
  const successful = results.filter((result) => result.ok)
  const failed = results.filter((result) => !result.ok)
  const latencies = successful.map((result) => result.latencyMs)
  const failedLatencies = failed.map((result) => result.latencyMs)
  const statusCounts = {}
  const businessCodeCounts = {}
  for (const result of results) {
    const status = String(result.httpStatus ?? 'NO_RESPONSE')
    statusCounts[status] = (statusCounts[status] ?? 0) + 1
    const code = String(result.businessCode ?? result.error ?? 'UNKNOWN')
    businessCodeCounts[code] = (businessCodeCounts[code] ?? 0) + 1
  }
  return {
    requests: results.length,
    successes: successful.length,
    errors: failed.length,
    errorRate: results.length ? Number((failed.length / results.length).toFixed(4)) : 0,
    throughputRps: Number((results.length / durationSeconds).toFixed(2)),
    // Latency percentiles cover successful requests only. Failed requests are
    // reported separately so a single 10s timeout cannot poison P95/P99.
    latencyMs: {
      p50: percentile(latencies, 50),
      p95: percentile(latencies, 95),
      p99: percentile(latencies, 99),
      max: latencies.length ? Math.max(...latencies) : null,
    },
    failedLatencyMs: failedLatencies.length
      ? {
          p50: percentile(failedLatencies, 50),
          p95: percentile(failedLatencies, 95),
          max: Math.max(...failedLatencies),
        }
      : null,
    httpStatus: statusCounts,
    businessCode: businessCodeCounts,
  }
}

async function setLoggerLevel(logger, level) {
  const url = `${base}/actuator/loggers/${logger}`
  const res = await fetch(url, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ configuredLevel: level }),
  })
  if (!res.ok) {
    throw new Error(
      `set logger ${logger}=${level} failed status=${res.status}; ` +
        'start the backend with --management.endpoints.web.exposure.include=health,loggers',
    )
  }
  const current = await fetch(url).then((r) => r.json())
  const applied = current.configuredLevel ?? current.effectiveLevel
  if (String(applied).toUpperCase() !== level) {
    throw new Error(`logger level not applied: wanted ${level}, got ${applied}`)
  }
}

function fileFingerprint(relPath) {
  try {
    const buf = readFileSync(relPath)
    return {
      path: relPath,
      bytes: buf.length,
      sha256Prefix: createHash('sha256').update(buf).digest('hex').slice(0, 12),
      mtime: statSync(relPath).mtime.toISOString(),
    }
  } catch {
    return null
  }
}

function gitHead() {
  try {
    return execSync('git rev-parse HEAD', { stdio: ['ignore', 'pipe', 'ignore'] }).toString().trim()
  } catch {
    return null
  }
}

function javaProcessCount() {
  try {
    const out = execSync('tasklist /FI "IMAGENAME eq java.exe" /NH', {
      stdio: ['ignore', 'pipe', 'ignore'],
    }).toString()
    return out.split('\n').filter((line) => /java\.exe/i.test(line)).length
  } catch {
    return null
  }
}

const environment = {
  capturedAt: new Date().toISOString(),
  node: process.version,
  platform: `${os.platform()} ${os.release()}`,
  cpu: os.cpus()[0]?.model ?? null,
  cores: os.cpus().length,
  totalMemGB: Number((os.totalmem() / 2 ** 30).toFixed(1)),
  gitHead: gitHead(),
  javaProcesses: javaProcessCount(),
  fingerprint: fileFingerprint(fingerprintPath),
}

const startedAt = new Date().toISOString()
const report = {
  startedAt,
  base,
  mode: abLogger ? 'ab-logger' : 'baseline',
  model: 'closed-loop fixed concurrency',
  durationSec,
  warmupSec,
  timeoutMs,
  levels,
  ab: abLogger ? { logger: abLogger, levels: abLevels, rounds: abRounds } : null,
  scenarios: selected.map(({ name, path, expect }) => ({ name, path, expect })),
  environment,
  results: [],
}

console.log(`[stress] base=${base} model=closed-loop duration=${durationSec}s warmup=${warmupSec}s`)
console.log(`[stress] scenarios=${selected.map((scenario) => scenario.name).join(', ')}`)
console.log(`[stress] env cpu=${environment.cpu} cores=${environment.cores} mem=${environment.totalMemGB}GB`)
console.log(
  `[stress] env git=${environment.gitHead ?? 'NO-GIT-REPO'} javaProcs=${environment.javaProcesses ?? 'n/a'} ` +
    `fingerprint=${environment.fingerprint?.sha256Prefix ?? 'n/a'}`,
)

if (abLogger) {
  if (selected.length !== 1) throw new Error('--ab-logger requires exactly one scenario via --only')
  if (levels.length !== 1) throw new Error('--ab-logger requires exactly one concurrency level')
  if (abLevels.length !== 2) throw new Error('--ab-levels requires exactly two levels, e.g. DEBUG,INFO')

  const scenario = selected[0]
  const concurrency = levels[0]
  process.stdout.write(`\n[ab] one-time warmup ${warmupSec}s ...`)
  await runPhase(scenario, concurrency, warmupSec, null)
  console.log(' done')

  for (let round = 1; round <= abRounds; round++) {
    // Reverse the order on even rounds so neither variant always runs first.
    const order = round % 2 === 1 ? [0, 1] : [1, 0]
    for (const variantIndex of order) {
      const level = abLevels[variantIndex]
      const variant = variantIndex === 0 ? 'A' : 'B'
      await setLoggerLevel(abLogger, level)
      const samples = []
      const measuredAt = new Date().toISOString()
      process.stdout.write(`[ab] round=${round} variant=${variant} level=${level} ...`)
      await runPhase(scenario, concurrency, durationSec, samples)
      const summary = summarize(samples, durationSec)
      const row = { round, variant, level, scenario: scenario.name, concurrency, measuredAt, ...summary }
      report.results.push(row)
      console.log(
        ` rps=${row.throughputRps} p50=${row.latencyMs.p50}ms p95=${row.latencyMs.p95}ms ` +
          `p99=${row.latencyMs.p99}ms errors=${row.errors}`,
      )
    }
  }

  const stats = (variant) => {
    const rows = report.results.filter((row) => row.variant === variant)
    const rps = rows.map((row) => row.throughputRps)
    const p95 = rows.map((row) => row.latencyMs.p95)
    const min = Math.min(...rps)
    const max = Math.max(...rps)
    const mid = median(rps)
    return {
      rounds: rows.length,
      throughputRps: { values: rps, median: mid, min, max },
      spreadPct: mid ? Number((((max - min) / mid) * 100).toFixed(1)) : null,
      p95Ms: { values: p95, median: median(p95) },
    }
  }
  const a = stats('A')
  const b = stats('B')
  const deltaPct = a.throughputRps.median
    ? Number((((b.throughputRps.median - a.throughputRps.median) / a.throughputRps.median) * 100).toFixed(1))
    : null
  const maxSpread = Math.max(a.spreadPct ?? 0, b.spreadPct ?? 0)
  report.abSummary = {
    A: { level: abLevels[0], ...a },
    B: { level: abLevels[1], ...b },
    throughputDeltaPct: deltaPct,
    maxWithinGroupSpreadPct: maxSpread,
    // The effect can only be claimed when it exceeds natural within-group noise.
    conclusive: deltaPct !== null && Math.abs(deltaPct) > maxSpread,
  }
  console.log(`\n[ab] A(${abLevels[0]}) median=${a.throughputRps.median} rps spread=${a.spreadPct}%`)
  console.log(`[ab] B(${abLevels[1]}) median=${b.throughputRps.median} rps spread=${b.spreadPct}%`)
  console.log(
    `[ab] delta=${deltaPct}% maxSpread=${maxSpread}% conclusive=${report.abSummary.conclusive}`,
  )
} else {
  for (const scenario of selected) {
    for (const concurrency of levels) {
      process.stdout.write(`\n[stress] ${scenario.name} concurrency=${concurrency} warmup...`)
      await runPhase(scenario, concurrency, warmupSec, null)
      const samples = []
      const measuredAt = new Date().toISOString()
      await runPhase(scenario, concurrency, durationSec, samples)
      const summary = summarize(samples, durationSec)
      const row = { scenario: scenario.name, concurrency, measuredAt, ...summary }
      report.results.push(row)
      console.log(' done')
      console.log(
        `  requests=${row.requests} success=${row.successes} errors=${row.errors} ` +
          `rps=${row.throughputRps} p50=${row.latencyMs.p50}ms ` +
          `p95=${row.latencyMs.p95}ms p99=${row.latencyMs.p99}ms max=${row.latencyMs.max}ms`,
      )
      if (row.errors) console.log(`  status=${JSON.stringify(row.httpStatus)} business=${JSON.stringify(row.businessCode)}`)
    }
  }
}

const stamp = new Date().toISOString().replace(/[:.]/g, '-')
const outputDir = 'deploy/api-stress/results'
await mkdir(outputDir, { recursive: true })
const outputPath = `${outputDir}/${report.mode}-${stamp}.json`
await writeFile(outputPath, `${JSON.stringify(report, null, 2)}\n`, 'utf8')
console.log(`\n[stress] report=${outputPath}`)
