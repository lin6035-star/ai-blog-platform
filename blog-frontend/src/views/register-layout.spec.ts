// @vitest-environment node
// 本文件只读源码文本做断言，不需要 DOM；跑在 jsdom 下会让 node:fs 被 externalize 而收集失败
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

const registerView = readFileSync(resolve(__dirname, 'RegisterView.vue'), 'utf8')
const loginCss = readFileSync(resolve(__dirname, '../styles/login-characters.css'), 'utf8')

describe('register page compact layout', () => {
  it('uses a register-specific compact layout instead of inline scaling', () => {
    expect(registerView).toContain('class="login-page register-page"')
    expect(registerView).not.toContain('transform: scale')
    expect(loginCss).toContain('.register-page .login-right')
    expect(loginCss).toContain('.register-page .login-star')
  })

  it('keeps the register footer visible after adding GitHub login', () => {
    expect(registerView).toContain('class="login-btn login-btn-github')
    expect(registerView).toContain('已有账号？')
    expect(loginCss).toContain('.register-page .login-divider')
    expect(loginCss).toContain('.register-page .login-btn-github')
    expect(loginCss).toContain('.register-page .login-signup')
    expect(loginCss).toContain('max-height: calc(100vh - 48px)')
    expect(loginCss).toContain('overflow-y: auto')
    expect(loginCss).toContain('min-height: 46px')
  })
})
