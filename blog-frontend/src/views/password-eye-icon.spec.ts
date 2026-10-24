// @vitest-environment node
// 本文件只读源码文本做断言，不需要 DOM；跑在 jsdom 下会让 node:fs 被 externalize 而收集失败
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

function readView(name: string) {
  return readFileSync(resolve(__dirname, name), 'utf8')
}

function expectHiddenBranchUsesSlashedEye(source: string, flagName: string) {
  const hiddenBranchStart = source.indexOf(`<template v-if="!${flagName}">`)
  const visibleBranchStart = source.indexOf(`<template v-else>`, hiddenBranchStart)

  expect(hiddenBranchStart).toBeGreaterThan(-1)
  expect(visibleBranchStart).toBeGreaterThan(hiddenBranchStart)

  const hiddenBranch = source.slice(hiddenBranchStart, visibleBranchStart)
  const visibleBranch = source.slice(visibleBranchStart, source.indexOf('</template>', visibleBranchStart))

  expect(hiddenBranch).toContain('x1="1" y1="1" x2="23" y2="23"')
  expect(visibleBranch).toContain('<circle cx="12" cy="12" r="3" />')
}

describe('password visibility eye icons', () => {
  it('uses slashed eye when login password is hidden', () => {
    expectHiddenBranchUsesSlashedEye(readView('LoginView.vue'), 'passwordVisible')
  })

  it('uses slashed eye when register passwords are hidden', () => {
    const registerView = readView('RegisterView.vue')

    expectHiddenBranchUsesSlashedEye(registerView, 'passwordVisible')
    expectHiddenBranchUsesSlashedEye(registerView, 'confirmVisible')
  })
})
