/**
 * 手工探测：验证 token 能签、鉴权能过，并可打一发真实 chat 看事件流。
 *
 * 用法：
 *   node deploy/ai-stress/probe.mjs                  # 只验 token（零成本，不调 LLM）
 *   node deploy/ai-stress/probe.mjs --chat "消息"     # 真打一发，打印 SSE 事件（花钱，几分钱）
 *   node deploy/ai-stress/probe.mjs --chat "消息" --events   # 额外打印每条事件的原始 data
 *
 * 压测前的第一步就是它：**别想当然**——路由是 LLM 分类器决定的，
 * 得先确认某句话真的会走进准入池（Agent / Workflow），否则压了个不占池的路径，数据全是假的。
 */
import { loadSecret, loadUserIds, signToken } from './jwt.mjs'

const BASE = 'http://localhost:8080'
const args = process.argv.slice(2)
const chatIdx = args.indexOf('--chat')
const chatMessage = chatIdx >= 0 ? args[chatIdx + 1] : null
const showAllEvents = args.includes('--events')

const secret = loadSecret()
const userIds = loadUserIds()
const userId = userIds[0]
const token = signToken(userId, secret)

console.log(`[probe] userId=${userId} token=${token.slice(0, 24)}...（自签，对生产无效）`)

// ---------- ① 鉴权验证：一个需要登录但不调 LLM 的接口 ----------
{
  const t0 = Date.now()
  const res = await fetch(`${BASE}/api/ai/conversations`, {
    headers: { Authorization: `Bearer ${token}` },
  })
  const body = await res.text()
  console.log(
    `[probe] GET /api/ai/conversations → ${res.status} (${Date.now() - t0}ms) ${body.slice(0, 160)}`,
  )
  if (res.status === 401) {
    console.error('[probe] ✗ 鉴权失败——签名逻辑或 secret 对不上，先修这个再往下走')
    process.exit(1)
  }
  console.log('[probe] ✓ token 有效')
}

if (!chatMessage) {
  console.log('\n[probe] 未指定 --chat，跳过真实调用（零成本模式）')
  process.exit(0)
}

// ---------- ② 打一发真实 chat，观察事件流与耗时 ----------
console.log(`\n[probe] 发起 chat: "${chatMessage}"`)

const t0 = Date.now()
let tFirstByte = null
let tFirstContent = null
const eventTally = new Map()

const res = await fetch(`${BASE}/api/ai/chat/stream`, {
  method: 'POST',
  headers: {
    'Content-Type': 'application/json',
    Authorization: `Bearer ${token}`,
  },
  body: JSON.stringify({ message: chatMessage }),
})

console.log(`[probe] HTTP ${res.status} @ ${Date.now() - t0}ms`)
if (!res.ok) {
  console.error(`[probe] ✗ 非 2xx：${(await res.text()).slice(0, 300)}`)
  process.exit(1)
}

const reader = res.body.getReader()
const decoder = new TextDecoder()
let buffer = ''

const handleEvent = (raw) => {
  const lines = raw.split(/\r?\n/)
  let eventName = 'message'
  const dataLines = []
  for (const line of lines) {
    if (line.startsWith('event:')) eventName = line.slice(6).trim()
    else if (line.startsWith('data:')) dataLines.push(line.slice(5).trim())
  }

  eventTally.set(eventName, (eventTally.get(eventName) ?? 0) + 1)

  if (eventName === 'content' && tFirstContent === null) {
    tFirstContent = Date.now()
  }
  if (showAllEvents || !['content', 'ping'].includes(eventName)) {
    const joined = dataLines.join('')
    const short = joined.length > 200 ? `${joined.slice(0, 200)}…` : joined
    console.log(`  [${Date.now() - t0}ms] ${eventName}: ${short}`)
  }
}

while (true) {
  const { done, value } = await reader.read()
  if (done) break
  if (tFirstByte === null) tFirstByte = Date.now()
  buffer += decoder.decode(value, { stream: true })
  let idx
  while ((idx = buffer.indexOf('\n\n')) >= 0) {
    handleEvent(buffer.slice(0, idx))
    buffer = buffer.slice(idx + 2)
  }
}
if (buffer.trim()) handleEvent(buffer)

const total = Date.now() - t0
console.log(`\n[probe] 事件统计: ${[...eventTally].map(([k, v]) => `${k}×${v}`).join(', ')}`)
console.log(
  `[probe] 首字节 ${tFirstByte === null ? '—' : `${tFirstByte - t0}ms`}` +
    ` · 首内容 ${tFirstContent === null ? '—' : `${tFirstContent - t0}ms`} · 总耗时 ${total}ms`,
)
