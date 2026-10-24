import { describe, expect, it } from 'vitest'
import { ARTICLE_STATUS, getArticleStatusLabel } from './articleStatus'

describe('article status constants', () => {
  it('names backend article status numbers in one place', () => {
    expect(ARTICLE_STATUS.DRAFT).toBe(0)
    expect(ARTICLE_STATUS.PUBLISHED).toBe(1)
    expect(ARTICLE_STATUS.HIDDEN).toBe(2)
  })

  it('maps article statuses to user-facing labels', () => {
    expect(getArticleStatusLabel(ARTICLE_STATUS.DRAFT)).toBe('草稿')
    expect(getArticleStatusLabel(ARTICLE_STATUS.PUBLISHED)).toBe('已发布')
    expect(getArticleStatusLabel(ARTICLE_STATUS.HIDDEN)).toBe('已隐藏')
  })
})
