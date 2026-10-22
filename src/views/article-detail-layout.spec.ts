// @vitest-environment node
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

const articleDetailView = readFileSync(resolve(__dirname, 'ArticleDetailView.vue'), 'utf8')
const globalStyles = readFileSync(resolve(__dirname, '../styles/index.css'), 'utf8')

describe('article detail async author layout', () => {
  it('does not render a follow button before the author profile is resolved', () => {
    expect(articleDetailView).toContain('const authorProfileLoading = ref(false)')
    expect(articleDetailView).toContain('authorProfile.value = null')
    expect(articleDetailView).toContain('v-if="authorProfileLoading"')
    expect(articleDetailView).toContain('class="detail-author-actions-placeholder"')
    expect(articleDetailView).toContain('v-else-if="authorProfile && !authorProfile.self"')
    expect(articleDetailView).toContain('class="detail-author-self"')
    expect(articleDetailView).toContain('作者本人')
    expect(articleDetailView).toContain('const authorId = article.value.authorId')
    expect(articleDetailView).toContain('if (article.value?.authorId !== authorId) return')
  })

  it('keeps the author action area at a stable height during loading', () => {
    expect(globalStyles).toContain('.detail-author-actions-placeholder')
    expect(globalStyles).toContain('min-height: 34px')
  })
})
