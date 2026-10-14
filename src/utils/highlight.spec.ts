import { describe, expect, it } from 'vitest'
import { highlightByKeyword } from './highlight'

describe('highlightByKeyword', () => {
  it('wraps the matched keyword in em', () => {
    expect(highlightByKeyword('Redis 缓存穿透实战', '缓存穿透')).toBe(
      'Redis <em>缓存穿透</em>实战',
    )
  })

  it('matches case-insensitively', () => {
    expect(highlightByKeyword('Redis 实战', 'redis')).toBe('<em>Redis</em> 实战')
  })

  it('keeps the original text when the keyword does not appear', () => {
    expect(highlightByKeyword('Redis 实战', 'MySQL')).toBe('Redis 实战')
  })

  it('keeps the original text when the keyword is blank', () => {
    expect(highlightByKeyword('Redis 实战', '   ')).toBe('Redis 实战')
    expect(highlightByKeyword('Redis 实战', '')).toBe('Redis 实战')
  })

  it('escapes HTML in the text instead of rendering it', () => {
    const result = highlightByKeyword('<script>alert(1)</script>', 'Redis')

    // 不断言 DOMPurify 的精确序列化（转义成实体还是剥掉标签属于它的实现细节），
    // 只断言真正要保证的东西：这段内容不能变成可执行的标签
    expect(result).not.toContain('<script')
    expect(result).toContain('alert(1)')
  })

  it('treats regex metacharacters in the keyword as literal text', () => {
    // 用户搜 "a.b" 时，不能把它当成"a 任意字符 b"的正则
    expect(highlightByKeyword('a.b 和 axb', 'a.b')).toBe('<em>a.b</em> 和 axb')
  })

  it('escapes the text when the keyword itself contains HTML', () => {
    const result = highlightByKeyword('看 <b>粗体</b> 标签', '<b>')

    expect(result).not.toContain('<b>粗体')
  })
})
