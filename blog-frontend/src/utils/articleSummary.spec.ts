import { describe, expect, it } from 'vitest'
import { extractArticleSummary } from './articleSummary'

describe('extractArticleSummary', () => {
  it('uses visible text from rendered markdown and html', () => {
    expect(extractArticleSummary('*<u>那很好子</u>*')).toBe('那很好子')
    expect(extractArticleSummary('测试 <u>fighting</u>')).toBe('测试 fighting')
  })

  it('uses visible markdown text without markdown syntax', () => {
    expect(extractArticleSummary('# 标题\n这是**加粗**和[链接](https://example.com)文字')).toBe(
      '标题 这是加粗和链接文字',
    )
  })

  it('stops before images and code blocks', () => {
    expect(extractArticleSummary('前面文字\n![图片](https://example.com/a.png)\n后面文字')).toBe(
      '前面文字',
    )
    expect(extractArticleSummary('前面文字\n<img src="https://example.com/a.png" />\n后面文字')).toBe(
      '前面文字',
    )
    expect(extractArticleSummary('前面文字\n```ts\nconst a = 1\n```\n后面文字')).toBe('前面文字')
  })

  it('limits plain text summaries to 25 characters', () => {
    expect(extractArticleSummary('一二三四五六七八九十一二三四五六七八九十一二三四五六')).toBe(
      '一二三四五六七八九十一二三四五六七八九十一二三四五…',
    )
  })
})
