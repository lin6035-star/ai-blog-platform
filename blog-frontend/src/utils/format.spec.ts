import { describe, expect, it } from 'vitest'
import { displayCredit, formatArticleDate, formatArticleDateTime } from './format'

describe('date formatting helpers', () => {
  it('formats published article dates for article cards', () => {
    expect(formatArticleDate('2026-07-05T16:21:04')).toMatch(/^2026\/07\/05$/)
  })

  it('returns the article fallback label when no published date exists', () => {
    expect(formatArticleDate('')).toBe('未发布')
  })

  it('formats article timestamps with time for management views', () => {
    expect(formatArticleDateTime('2026-07-05T16:21:04')).toContain('2026')
    expect(formatArticleDateTime('2026-07-05T16:21:04')).toContain('16')
  })

  it('keeps empty management timestamps empty', () => {
    expect(formatArticleDateTime('')).toBe('')
  })

  it('supports a custom fallback label for empty timestamps', () => {
    expect(formatArticleDateTime('', '未发布')).toBe('未发布')
  })
})

/**
 * 展示口径：把「在途预占」加回余额。
 *
 * 锁的是一个真实体验问题：预扣按该路径硬上限占款（一次 Workflow 先占 10000、
 * 实际只花几十），如果直接显示 balance，用户在执行期间会看到额度掉到远低于预期、
 * 甚至变成负数，以为额度出错了——而它只是被临时占着，结算后按实际用量退回。
 */
describe('displayCredit', () => {
  it('任务执行中：在途预占被加回，数字不跳', () => {
    expect(displayCredit({ balance: 30_000, pendingReserveCredit: 10_000 })).toBe(40_000)
  })

  it('余额被占成负数时，展示仍然是预扣前的额度', () => {
    expect(displayCredit({ balance: -9_900, pendingReserveCredit: 10_000 })).toBe(100)
  })

  it('结算完成后：预占归零，显示真实扣减后的额度', () => {
    expect(displayCredit({ balance: 39_960, pendingReserveCredit: 0 })).toBe(39_960)
  })

  it('真欠费（没有在途预占）：原样显示负数，不掩盖', () => {
    expect(displayCredit({ balance: -50, pendingReserveCredit: 0 })).toBe(-50)
  })

  it('字段缺失按 0 处理，不炸也不假装有额度', () => {
    expect(displayCredit({ balance: 100 })).toBe(100)
    expect(displayCredit(null)).toBe(0)
    expect(displayCredit(undefined)).toBe(0)
  })
})
