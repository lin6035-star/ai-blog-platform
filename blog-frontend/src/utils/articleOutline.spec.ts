import { describe, expect, it } from 'vitest'
import { renderArticleWithOutline } from './articleOutline'

describe('renderArticleWithOutline', () => {
  it('renders markdown headings with stable ids and returns outline items', () => {
    const result = renderArticleWithOutline(`
# 页面标题

## 前言

### 为什么需要目录？

## 前言
`)

    expect(result.outline).toEqual([
      { id: 'heading-前言', text: '前言', level: 2 },
      { id: 'heading-为什么需要目录', text: '为什么需要目录？', level: 3 },
      { id: 'heading-前言-2', text: '前言', level: 2 },
    ])
    expect(result.html).toContain('<h2 id="heading-前言">前言</h2>')
    expect(result.html).toContain('<h3 id="heading-为什么需要目录">为什么需要目录？</h3>')
    expect(result.html).toContain('<h2 id="heading-前言-2">前言</h2>')
    expect(result.outline.some((item) => item.text === '页面标题')).toBe(false)
  })

  it('does not treat headings inside fenced code blocks as outline items', () => {
    const result = renderArticleWithOutline(`
## 正文标题

\`\`\`md
## 代码里的标题
\`\`\`
`)

    expect(result.outline).toEqual([
      { id: 'heading-正文标题', text: '正文标题', level: 2 },
    ])
    expect(result.html).toContain('代码里的标题')
    expect(result.html).not.toContain('id="heading-代码里的标题"')
  })

  it('清除文章内容中的危险标签，outline 仍然正常', () => {
    const result = renderArticleWithOutline(`
## 安全标题

<script>alert(1)</script>

<img src=x onerror=alert(1)>

<a href="javascript:alert(1)">点击</a>
`)

    expect(result.html).not.toContain('<script')
    expect(result.html).not.toContain('onerror')
    expect(result.html).not.toContain('javascript:')
    expect(result.outline).toEqual([
      { id: 'heading-安全标题', text: '安全标题', level: 2 },
    ])
    expect(result.html).toContain('<h2 id="heading-安全标题">安全标题</h2>')
  })
})
