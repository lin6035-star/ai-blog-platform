import { describe, expect, it } from 'vitest'
import { renderMarkdown } from './markdown'

describe('renderMarkdown XSS 防护', () => {
  it('清除 script 标签', () => {
    const html = renderMarkdown('<script>alert(1)</script>正常内容')
    expect(html).not.toContain('<script')
    expect(html).not.toContain('alert(1)')
    expect(html).toContain('正常内容')
  })

  it('清除 img onerror 属性', () => {
    const html = renderMarkdown('<img src=x onerror=alert(1)>')
    expect(html).not.toContain('onerror')
    expect(html).not.toContain('alert(1)')
  })

  it('清除 javascript: 链接', () => {
    const html = renderMarkdown('<a href="javascript:alert(1)">点击</a>')
    expect(html).not.toContain('javascript:')
    expect(html).toContain('点击')
  })

  it('清除 svg onload 事件属性', () => {
    const html = renderMarkdown('<svg onload=alert(1)></svg>')
    // DOMPurify 白名单保留 svg 标签本身（空 svg 无害），但事件属性必须剥离
    expect(html).not.toContain('onload')
    expect(html).not.toContain('alert(1)')
  })

  it('代码块仍然正常渲染', () => {
    const html = renderMarkdown('```js\nconst a = 1;\n```')
    expect(html).toContain('<pre><code')
    expect(html).toContain('hljs-keyword') // hljs 高亮 span 保留
    expect(html).toContain('const')
  })

  it('正常内容保留：标题、列表、表格、HTTPS 链接', () => {
    const markdown = [
      '# 标题',
      '',
      '- 列表项',
      '',
      '| a | b |',
      '| --- | --- |',
      '| 1 | 2 |',
      '',
      '[链接](https://example.com)',
    ].join('\n')

    const html = renderMarkdown(markdown)
    expect(html).toContain('<h1')
    expect(html).toContain('<li>列表项</li>')
    expect(html).toContain('<table>')
    expect(html).toContain('href="https://example.com"')
  })
})
