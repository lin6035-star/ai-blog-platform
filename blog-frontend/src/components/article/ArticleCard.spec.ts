import { shallowMount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import type { Article } from '@/types/article'
import ArticleCard from './ArticleCard.vue'

const article: Article = {
  id: '1',
  categoryId: '2',
  authorId: '9',
  authorName: '海林',
  categoryName: 'Java 后端',
  title: 'Redis 缓存穿透实战',
  summary: '讲缓存穿透与布隆过滤器',
  content: '',
  coverUrl: '',
  status: 1,
  viewCount: 10,
  publishedAt: '2026-09-01T10:00:00',
  createdAt: '2026-09-01T10:00:00',
  updatedAt: '2026-09-01T10:00:00',
}

function mountCard(props: Record<string, unknown>) {
  return shallowMount(ArticleCard, {
    props: { article, ...props },
    global: { stubs: { NIcon: true } },
  })
}

/**
 * 搜索高亮的渲染测试。
 *
 * <p>这一层必须有：后端把高亮算好、透出到 VO 之后，
 * **前端漏接不会有任何测试变红**——这正是这个功能上一版的实际状态
 * （ArticleDetailVO.highlight 一直是 null 之外没人读）。
 */
describe('ArticleCard search highlight', () => {
  it('renders the title and fragment highlight coming from ES', () => {
    const wrapper = mountCard({
      article: {
        ...article,
        highlightTitle: 'Redis <em>缓存穿透</em>实战',
        highlight: '……布隆过滤器可以挡住<em>缓存穿透</em>……',
      },
    })

    expect(wrapper.find('h2').html()).toContain('<em>缓存穿透</em>')
    expect(wrapper.find('p').html()).toContain('布隆过滤器')
  })

  it('falls back to local highlight when ES gave no highlight', () => {
    // ES 不可用会降级回 LIKE，那时后端一个高亮都没有，只剩 keyword 这条兜底
    const wrapper = mountCard({ keyword: '缓存穿透' })

    expect(wrapper.find('h2').html()).toContain('<em>缓存穿透</em>')
    expect(wrapper.find('p').html()).toContain('<em>缓存穿透</em>')
  })

  it('keeps plain text rendering outside search', () => {
    const wrapper = mountCard({})

    expect(wrapper.find('h2').html()).not.toContain('<em>')
    expect(wrapper.find('h2').text()).toBe('Redis 缓存穿透实战')
    expect(wrapper.find('p').text()).toBe('讲缓存穿透与布隆过滤器')
  })

  it('sanitizes the highlight before rendering it', () => {
    const wrapper = mountCard({
      article: {
        ...article,
        highlight: '<em>命中</em><img src=x onerror=alert(1)>',
      },
    })

    expect(wrapper.find('p').html()).toContain('<em>命中</em>')
    expect(wrapper.find('p').html()).not.toContain('onerror')
  })

  it('shows the empty-summary fallback when neither summary nor highlight exists', () => {
    const wrapper = mountCard({ article: { ...article, summary: '', highlight: undefined } })

    expect(wrapper.find('p').text()).toContain('这篇文章暂时没有摘要')
  })
})
