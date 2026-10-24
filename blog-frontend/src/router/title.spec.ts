// @vitest-environment node
// 本文件只读源码文本做断言，不需要 DOM；跑在 jsdom 下会让 node:fs 被 externalize 而收集失败
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

const routerSource = readFileSync(resolve(__dirname, 'index.ts'), 'utf8')

describe('router document title behavior', () => {
  it('does not update the browser title when auth guard cancels navigation', () => {
    expect(routerSource).toContain('router.afterEach((to, _from, failure)')
    expect(routerSource).toContain('if (failure) return')
    expect(routerSource.indexOf('if (failure) return')).toBeLessThan(
      routerSource.indexOf('document.title ='),
    )
  })
})
