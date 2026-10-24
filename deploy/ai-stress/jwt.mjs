/**
 * 压测用的 JWT 签发（复刻 JwtUtil 的签名逻辑）。
 *
 * **为什么不走登录接口换 token**：200 个 jmeter_test_* 账号是当时用脚本建的、密码各自随机
 * （库里 200 个不同的 bcrypt 哈希），拿不到明文；而 `JwtUtil.generateToken(userId)` 只依赖
 * userId + secret，payload 就 `{userId, jti, exp}` 三个字段。直接复刻最省事，也省掉了
 * 「200 个 token 落成 CSV」这一步——token 是文件就会有泄漏面。
 *
 * ⚠️ secret 从 application.yml 读，那是**本地开发默认值**；生产注入的是 JWT_SECRET。
 * 这里签出来的 token 对生产无效——这是刻意的，压测只打本机。
 */
import { createHmac, randomUUID } from 'node:crypto'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, resolve } from 'node:path'

const HERE = dirname(fileURLToPath(import.meta.url))
const REPO_ROOT = resolve(HERE, '..', '..')

/**
 * 解析 Spring 的配置占位符语法。
 *
 * yml 里写的是 `secret: ${JWT_SECRET:blog-system-dev-secret-change-later}`——
 * **必须按 Spring 的语义求值**，直接把整串当 secret 用会签出无效 token（401）。
 * 这条踩过一次：第一版正则读到的是 `${JWT_SECRET:...}` 字面量。
 */
function resolvePlaceholder(raw) {
  const m = raw.match(/^\$\{([^:}]+)(?::([^}]*))?\}$/)
  if (!m) return raw // 纯字面量
  const [, envName, defaultVal] = m
  const fromEnv = process.env[envName]
  if (fromEnv !== undefined && fromEnv !== '') return fromEnv
  if (defaultVal !== undefined) return defaultVal
  throw new Error(`配置占位符 \${${envName}} 既没有默认值，环境变量也没设置`)
}

/** 从 application.yml 读 jwt.secret（块内找，跳过注释行，再解占位符） */
export function loadSecret() {
  const yml = readFileSync(
    resolve(REPO_ROOT, 'blog-system/src/main/resources/application.yml'),
    'utf8',
  )
  let inJwtBlock = false
  for (const line of yml.split(/\r?\n/)) {
    if (/^jwt:\s*$/.test(line)) {
      inJwtBlock = true
      continue
    }
    if (!inJwtBlock) continue
    if (/^\S/.test(line)) break // 出了 jwt 块
    const m = line.match(/^\s+secret:\s*(\S+)/)
    if (m) return resolvePlaceholder(m[1])
  }
  throw new Error('application.yml 里没找到 jwt.secret')
}

/**
 * 与 JwtUtil.generateToken 等价：HS256 + base64url（无 padding）+ 7 天有效期。
 *
 * ⚠️ **userId 必须按字符串原样拼进 payload，不能经过 JS 的 Number**。
 * 雪花 ID 有 19 位（如 2100945997778681858），超过 `Number.MAX_SAFE_INTEGER`
 * （9007199254740991），`Number()` 会把末尾几位抹成 0 —— 于是签出来的 token 挂在
 * 一个**根本不存在的用户**名下。因为后端不校验 userId 是否真的存在（见下方说明），
 * 请求照样 200，**不查库根本发现不了**（实际踩到：压出来的 session 全是假 ID）。
 *
 * 手工拼 JSON 而不是 `JSON.stringify({userId, ...})`，就是为了绕开这一步。
 */
export function signToken(userId, secret) {
  const b64urlRaw = (s) => Buffer.from(s).toString('base64url')
  const header = b64urlRaw(JSON.stringify({ alg: 'HS256', typ: 'JWT' }))
  const exp = Math.floor(Date.now() / 1000) + 7 * 24 * 3600
  const payload = b64urlRaw(`{"userId":${userId},"jti":"${randomUUID()}","exp":${exp}}`)
  const unsigned = `${header}.${payload}`
  const signature = createHmac('sha256', secret).update(unsigned).digest('base64url')
  return `${unsigned}.${signature}`
}

/** 读 user-ids.txt（每行一个 userId）。
 *  ⚠️ 保持**字符串**，不要 `Number()` —— 见 signToken 的说明。 */
export function loadUserIds() {
  return readFileSync(resolve(HERE, 'user-ids.txt'), 'utf8')
    .split(/\r?\n/)
    .map((s) => s.trim())
    .filter(Boolean)
}
