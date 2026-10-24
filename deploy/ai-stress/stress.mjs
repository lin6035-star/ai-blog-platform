/**
 * AI 长任务准入压测（L1：行为验证）。
 *
 * 用法：
 *   node deploy/ai-stress/stress.mjs --users 40                    # 40 个用户各 1 发
 *   node deploy/ai-stress/stress.mjs --users 40 --probe 1          # 40 发 + 1 个探测（看第 41 个是否 503）
 *   node deploy/ai-stress/stress.mjs --users 1 --per-user 3        # 单用户 3 发（看第 3 个是否 429）
 *
 * 为什么不用 JMeter：本次被测的是 **SSE 长连接**——要量首字节、要保持连接几十秒、
 * 要按用户分组构造场景。JMeter 处理这些得靠插件硬凑（见设计稿 §3.2）。
 *
 * ⚠️ 这个脚本会**真调 LLM**（每次几分钱）。跑之前确认：
 *   ① 本地后端已启动  ② 你接受这点成本  ③ 地址是 localhost（写死了，不会误压线上）
 */
import { loadSecret, loadUserIds, signToken } from './jwt.mjs'

const BASE = 'http://localhost:8080'
const MESSAGE = '我想系统学 Redis'

// ---------- 参数 ----------
const args = process.argv.slice(2)
const num = (flag, dflt) => {
  const i = args.indexOf(flag)
  return i >= 0 ? Number(args[i + 1]) : dflt
}
const users = num('--users', 8)
const perUser = num('--per-user', 1)
const probeCount = num('--probe', 0)
const timeoutMs = num('--timeout-ms', 180_000)

const secret = loadSecret()
const allIds = loadUserIds()
if (users > allIds.length) {
  console.error(`[stress] 需要 ${users} 个账号，user-ids.txt 里只有 ${allIds.length} 个`)
  process.exit(1)
}

// 发请求的清单：前 users 个账号，每人 perUser 发
const plan = []
for (const userId of allIds.slice(0, users)) {
  for (let i = 0; i < perUser; i++) plan.push({ userId, seq: i + 1 })
}
// 探测请求：用一个「不在上面名单里」的账号，避免被用户级上限挡住而误判成"池满"
const probeUserIds = allIds.slice(users, users + probeCount)

console.log(
  `[stress] 计划：${plan.length} 个正式请求（${users} 用户 × ${perUser}）` +
    (probeCount ? ` + ${probeCount} 个探测请求` : ''),
)
console.log(`[stress] 消息："${MESSAGE}" · 单请求超时 ${timeoutMs}ms\n`)

/** 打一发，返回观测结果 */
async function fire({ userId, seq }, kind = 'main') {
  const token = signToken(userId, secret)
  const t0 = Date.now()
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), timeoutMs)

  const result = { kind, userId, seq, t0, status: null, firstByte: null, end: null, error: null, body: '' }

  try {
    const res = await fetch(`${BASE}/api/ai/chat/stream`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
      body: JSON.stringify({ message: MESSAGE }),
      signal: controller.signal,
    })
    result.status = res.status

    if (!res.ok) {
      // 429 / 503 走的是「非 2xx + Result JSON」，必须读出来才知道拒绝原因
      result.body = (await res.text()).slice(0, 200)
      result.firstByte = result.end = Date.now() - t0
      return result
    }

    // 2xx：读完整个 SSE 流，保持连接（不读的话连接会提前断，压不出真实占用）
    const reader = res.body.getReader()
    for (;;) {
      const { done, value } = await reader.read()
      if (done) break
      if (result.firstByte === null) result.firstByte = Date.now() - t0
      void value
    }
    result.end = Date.now() - t0
  } catch (e) {
    result.error = e.name === 'AbortError' ? `超时(${timeoutMs}ms)` : e.message
    result.end = Date.now() - t0
  } finally {
    clearTimeout(timer)
  }
  return result
}

// ---------- 同步发起（不是 ramp-up：要的是"同一瞬间全部到位"） ----------
const t0 = Date.now()
const mainPromise = Promise.all(plan.map((p) => fire(p, 'main')))
// 探测请求和正式请求一起发出——晚发就测不到"满员时刻"的拒绝了
const probePromise = Promise.all(probeUserIds.map((id) => fire({ userId: id, seq: 1 }, 'probe')))

const [mainResults, probeResults] = await Promise.all([mainPromise, probePromise])
const wallMs = Date.now() - t0

// ---------- 汇总 ----------
const pct = (arr, p) => {
  if (!arr.length) return '—'
  const sorted = [...arr].sort((a, b) => a - b)
  return `${sorted[Math.min(sorted.length - 1, Math.floor((sorted.length * p) / 100))]}ms`
}
const tally = (rs, key) =>
  [...rs.reduce((m, r) => m.set(r[key], (m.get(r[key]) ?? 0) + 1), new Map())]
    .map(([k, v]) => `${k}×${v}`)
    .join(' · ')

const report = (title, rs) => {
  console.log(`\n=== ${title}（${rs.length} 个）===`)
  console.log(`HTTP 状态: ${tally(rs.filter((r) => r.status !== null), 'status') || '（无响应）'}`)
  const ok = rs.filter((r) => r.status === 200)
  if (ok.length) {
    console.log(`首字节: P50 ${pct(ok.map((r) => r.firstByte), 50)} · P95 ${pct(ok.map((r) => r.firstByte), 95)}`)
    console.log(`总耗时: P50 ${pct(ok.map((r) => r.end), 50)} · P95 ${pct(ok.map((r) => r.end), 95)} · 最长 ${Math.max(...ok.map((r) => r.end))}ms`)
  }
  const bad = rs.filter((r) => r.status !== 200)
  if (bad.length) {
    console.log('拒绝明细:')
    for (const r of bad) {
      console.log(`  - [${r.kind}] user=${r.userId} seq=${r.seq} → ${r.status ?? r.error} ${r.body}`)
    }
  }
}

console.log(`\n[stress] 全部完成，墙钟耗时 ${wallMs}ms`)
report('正式请求', mainResults)
if (probeResults.length) report('探测请求', probeResults)

console.log(
  '\n[stress] 接下来看后端日志核对准入行为：\n' +
    '  grep "\\[AI-TASK\\]" <后端日志> | grep -E "start|done|rejected"\n' +
    '  要确认的是 running / queued 的峰值，以及拒绝原因（USER_LIMIT / POOL_FULL）',
)
