// @vitest-environment node
// 本文件只读源码文本做断言，不需要 DOM；跑在 jsdom 下会让 node:fs 被 externalize 而收集失败
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

const srcRoot = resolve(__dirname, '..')

function readSource(path: string) {
  return readFileSync(resolve(srcRoot, path), 'utf8')
}

describe('home information-flow layout', () => {
  it('uses custom top-right auth links instead of Element Plus buttons', () => {
    const header = readSource('components/layout/AppHeader.vue')

    expect(header).toContain('class="auth-link login-link"')
    expect(header).toContain('class="auth-link register-link"')
    expect(header).toContain('class="header-search"')
    expect(header).toContain('class="creator-center"')
    expect(header).toContain('class="creator-popover"')
    expect(header).toContain('class="creator-trigger" type="button"')
    expect(header).not.toContain('to="/write"')
    expect(header).not.toContain('to="/drafts"')
    expect(header).not.toContain("router.push('/login')")
    expect(header).not.toContain("router.push('/register')")
  })

  it('keeps categories in the left rail without showing the home tag strip', () => {
    const home = readSource('views/HomeView.vue')

    expect(home).not.toContain('RightSidebar')
    expect(home).not.toContain('home-hero')
    expect(home).not.toContain('class="tag-strip"')
    expect(home).toContain('class="category-rail"')
    expect(home).toContain('class="feed-layout home-feed-layout"')
  })

  it('mounts the hot rank card in the home right rail', () => {
    const home = readSource('views/HomeView.vue')
    const hotRankCard = readSource('components/article/ArticleHotRankCard.vue')
    const css = readSource('styles/index.css')

    expect(home).toContain("import ArticleHotRankCard from '@/components/article/ArticleHotRankCard.vue'")
    expect(home).toContain('class="home-side-rail"')
    expect(home).toContain('<ArticleHotRankCard />')
    expect(hotRankCard).toContain('热度榜')
    expect(hotRankCard).toContain('articleApi.getHotList({ page: 1, pageSize: 5 })')
    expect(hotRankCard).toContain("router.push({ name: 'hot-rank' })")
    expect(css).toContain('grid-template-columns: 220px minmax(0, 1fr) 220px')
    expect(css).toContain('.home-side-rail')
    expect(css).toContain('min-width: 0')
  })

  it('tightens the home first-screen vertical spacing', () => {
    const css = readSource('styles/index.css')

    expect(css).toContain('.home-feed-layout')
    expect(css).toContain('margin-top: 0px')
    // 首页首屏比通用列表页更紧凑：上内边距收掉（通用的是 12px）
    expect(css).toContain('.home-feed-layout .feed-tabs')
    expect(css).toContain('padding: 10px 20px 0')
  })

  it('defines a full hot rank page route', () => {
    const router = readSource('router/index.ts')
    const hotRankView = readSource('views/HotRankView.vue')

    expect(router).toContain("path: '/rank/hot'")
    expect(router).toContain("name: 'hot-rank'")
    expect(router).toContain("meta: { title: '热度榜' }")
    expect(hotRankView).toContain('热度榜')
    expect(hotRankView).toContain('articleApi.getHotList')
    expect(hotRankView).toContain('{{ article.authorName ||')
    expect(hotRankView).toContain('{{ article.viewCount || 0 }} 浏览')
    expect(hotRankView).toContain('{{ article.commentCount || 0 }} 评论')
    expect(hotRankView).toContain('{{ article.favoriteCount || 0 }} 收藏')
    expect(hotRankView).not.toContain('hotScore')
  })

  it('selects a category from the left rail and passes categoryId when loading articles', () => {
    const home = readSource('views/HomeView.vue')

    expect(home).toContain('selectedCategoryId')
    expect(home).toContain('function onCategorySelect(category: Category)')
    expect(home).toContain('categoryId:')
    expect(home).toContain('@click="onCategorySelect(category)"')
    expect(home).toContain(':class="{ active: category.id === selectedCategoryId }"')
  })

  it('uses the top feed tabs as recommend/latest sort controls', () => {
    const home = readSource('views/HomeView.vue')
    const feed = readSource('components/article/ArticleFeed.vue')

    expect(home).toContain("selectedSort = ref<ArticleSort>('recommend')")
    expect(home).toContain('sort: selectedSort.value')
    expect(home).toContain('function onSortChange(sort: ArticleSort)')
    expect(home).toContain(':sort="selectedSort"')
    expect(home).toContain('@sort-change="onSortChange"')
    expect(feed).toContain("sortTabs")
    expect(feed).toContain("value: 'recommend'")
    expect(feed).toContain("value: 'latest'")
    expect(feed).not.toContain('<button type="button">后端</button>')
    expect(feed).not.toContain('<button type="button">前端</button>')
  })

  it('loads more home articles by infinite scroll instead of page buttons', () => {
    const home = readSource('views/HomeView.vue')
    const feed = readSource('components/article/ArticleFeed.vue')

    expect(home).toContain('const loadingMore = ref(false)')
    expect(home).toContain('const hasMore = ref(false)')
    expect(home).toContain('async function loadArticles(reset = false)')
    expect(home).toContain('const isFirstPage = reset')
    // 翻页靠后端签发的 nextCursor，不是页码；"还有没有下一页"也由它驱动
    expect(home).toContain('cursor: isFirstPage ? undefined : (cursor.value ?? undefined)')
    expect(home).toContain('hasMore.value = Boolean(data.nextCursor)')
    expect(home).toContain('articles.value = isFirstPage ? list : [...articles.value, ...list]')
    expect(home).toContain('function loadMoreArticles()')
    expect(home).toContain('@load-more="loadMoreArticles"')
    expect(feed).toContain('loadMore: []')
    expect(feed).toContain('const loadMoreTriggerRef = ref<HTMLElement | null>(null)')
    expect(feed).toContain('new IntersectionObserver')
    expect(feed).toContain("emit('loadMore')")
    expect(feed).toContain('class="feed-load-more-trigger"')
    expect(feed).not.toContain('<n-pagination')
    expect(feed).not.toContain("'page-change'")
  })

  it('shows article author nickname and category tag in the card meta row', () => {
    const articleCard = readSource('components/article/ArticleCard.vue')
    const articleType = readSource('types/article.ts')

    expect(articleType).toContain('authorName?: string')
    expect(articleType).toContain('categoryName?: string')
    expect(articleCard).toContain('作者：{{ authorDisplayName }}')
    expect(articleCard).toContain('class="article-category-tag"')
    expect(articleCard).toContain('#{{ article.categoryName }}')
    expect(articleCard).not.toContain('作者 #{{ article.authorId }}')
  })

  it('lets users like and favorite articles directly from the home feed cards', () => {
    const home = readSource('views/HomeView.vue')
    const feed = readSource('components/article/ArticleFeed.vue')
    const articleCard = readSource('components/article/ArticleCard.vue')

    expect(articleCard).toContain('Heart')
    expect(articleCard).toContain('Star')
    expect(articleCard).toContain('{{ article.likeCount || 0 }}')
    expect(articleCard).toContain('{{ article.favoriteCount || 0 }}')
    expect(articleCard).toContain("@click.prevent.stop=\"emit('like', article)\"")
    expect(articleCard).toContain("@click.prevent.stop=\"emit('favorite', article)\"")
    expect(feed).toContain("@like=\"emit('like', $event)\"")
    expect(feed).toContain("@favorite=\"emit('favorite', $event)\"")
    expect(home).toContain('function handleArticleLike(article: Article)')
    expect(home).toContain('function handleArticleFavorite(article: Article)')
    expect(home).toContain('message.warning(\'请先登录后再操作亲\')')
    expect(home).toContain('@like="handleArticleLike"')
    expect(home).toContain('@favorite="handleArticleFavorite"')
    expect(home).not.toContain("router.push('/login')")
  })

  it('shows article author nickname and category tag in the detail meta row', () => {
    const detail = readSource('views/ArticleDetailView.vue')

    expect(detail).toContain('authorDisplayName')
    expect(detail).toContain('作者：{{ authorDisplayName }}')
    expect(detail).toContain('class="article-category-tag"')
    expect(detail).toContain('#{{ article.categoryName }}')
    expect(detail).not.toContain('作者 #{{ article.authorId }}')
  })

  it('keeps avatar hover circular without the old red border ring', () => {
    const css = readSource('styles/index.css')
    const hoverStart = css.indexOf('.header-avatar-link:hover')
    const hoverBlock = css.slice(hoverStart, css.indexOf('}', hoverStart))

    expect(css).toContain('.header-avatar-link:hover')
    expect(hoverBlock).toContain('scale(1.08)')
    expect(hoverBlock).not.toContain('border-color')
  })

  it('caches the home route and restores browser back scroll positions', () => {
    const app = readSource('App.vue')
    const router = readSource('router/index.ts')
    const home = readSource('views/HomeView.vue')

    expect(router).toContain('scrollBehavior')
    expect(router).toContain('if (savedPosition)')
    expect(router).toContain('meta: { title: \'首页\', keepAlive: true }')
    expect(app).toContain('<router-view v-slot="{ Component }">')
    expect(app).toContain('<KeepAlive include="HomeView">')
    expect(app).toContain('<component :is="Component" />')
    expect(home).toContain("defineOptions({ name: 'HomeView' })")
  })

  it('keeps the AI assistant mounted at app root instead of inside MainLayout', () => {
    const app = readSource('App.vue')
    const mainLayout = readSource('layouts/MainLayout.vue')

    expect(app).toContain("import AiAssistant from '@/components/ai/AiAssistant.vue'")
    expect(app).toContain('<AiAssistant />')
    expect(mainLayout).not.toContain('AiAssistant')
  })

  it('lets page content receive clicks outside the visible AI assistant panel', () => {
    const assistant = readSource('components/ai/AiAssistant.vue')

    expect(assistant).toMatch(/\.ai-assistant\s*\{[^}]*pointer-events:\s*none;/s)
    expect(assistant).toMatch(/\.ai-fab\s*\{[^}]*pointer-events:\s*auto;/s)
    expect(assistant).toMatch(/\.ai-panel\s*\{[^}]*pointer-events:\s*auto;/s)
  })

  it('refreshes home state only when users actively click header home entries', () => {
    const header = readSource('components/layout/AppHeader.vue')
    const home = readSource('views/HomeView.vue')

    expect(header).toContain('function goHomeFresh()')
    expect(header).toContain("query: { refresh: String(Date.now()) }")
    expect(header).toContain('@click.prevent="goHomeFresh"')
    expect(home).toContain('const router = useRouter()')
    expect(home).toContain('function resetHomeState()')
    expect(home).toContain('route.query.refresh')
    expect(home).toContain('resetHomeState()')
    expect(home).toContain('router.replace')
    expect(home).toContain('delete cleanQuery.refresh')
  })

  it('refreshes cached home articles when auth identity changes', () => {
    const home = readSource('views/HomeView.vue')

    expect(home).toContain('const authSignature = computed')
    expect(home).toContain('const loadedAuthSignature = ref')
    expect(home).toContain('function refreshArticlesForAuthChange()')
    expect(home).toContain('onActivated(refreshArticlesForAuthChange)')
    expect(home).toContain('watch(authSignature, refreshArticlesForAuthChange)')
  })

  it('uses history back for the article detail return button', () => {
    const detail = readSource('views/ArticleDetailView.vue')

    expect(detail).toContain('const router = useRouter()')
    expect(detail).toContain('function goBack()')
    expect(detail).toContain('router.back()')
    expect(detail).toContain('@click="goBack"')
    expect(detail).not.toContain('class="back-link" to="/"')
  })

  it('keeps the article detail return bar sticky while reading', () => {
    const detail = readSource('views/ArticleDetailView.vue')
    const css = readSource('styles/index.css')

    expect(detail).toContain('detailBackBarRef')
    expect(detail).toContain('isDetailBackBarStuck')
    expect(detail).toContain('updateDetailBackBarStuck')
    expect(detail).toContain('class="detail-back-bar"')
    expect(detail).toContain(':class="{ stuck: isDetailBackBarStuck }"')
    expect(detail.indexOf('class="detail-back-bar"')).toBeLessThan(
      detail.indexOf('class="detail-layout"'),
    )

    const barStart = css.indexOf('.detail-back-bar')
    const barBlock = css.slice(barStart, css.indexOf('}', barStart))

    expect(barBlock).toContain('position: sticky')
    expect(barBlock).toContain('top: 64px')
    expect(barBlock).toContain('z-index: 19')
    expect(barBlock).toContain('width: 100vw')
    expect(barBlock).toContain('margin-left: calc(50% - 50vw)')
    expect(barBlock).toContain('background: transparent')

    const stuckStart = css.indexOf('.detail-back-bar.stuck')
    const stuckBlock = css.slice(stuckStart, css.indexOf('}', stuckStart))

    expect(stuckBlock).toContain('background: rgba(239, 246, 243, 0.96)')
    expect(stuckBlock).toContain('backdrop-filter')
  })

  it('does not show the article summary on the detail page', () => {
    const detail = readSource('views/ArticleDetailView.vue')

    expect(detail).not.toContain('{{ article.summary }}')
    expect(detail).toContain('请返回首页查看其他公开文章。')
  })

  it('adds a sticky author card and content outline to the article detail right rail', () => {
    const detail = readSource('views/ArticleDetailView.vue')
    const css = readSource('styles/index.css')

    expect(detail).toContain("import { renderArticleWithOutline } from '@/utils/articleOutline'")
    expect(detail).toContain('renderedArticle.value.outline')
    expect(detail).toContain('class="detail-right-rail"')
    expect(detail).toContain('class="detail-author-card"')
    expect(detail).toContain('class="detail-outline-card"')
    expect(detail).toContain('内容纲要')
    expect(detail).toContain('@click="scrollToOutlineItem(item.id)"')
    expect(detail).toContain('关注')
    expect(detail).toContain('私信')

    expect(css).toContain('grid-template-columns: 56px minmax(0, 1fr) 260px')
    expect(css).toContain('.detail-right-rail')
    expect(css).toContain('position: sticky')
    expect(css).toContain('.detail-author-card')
    expect(css).toContain('.detail-outline-card')
    expect(css).toContain('scroll-margin-top: 92px')
  })

  it('adds a single immersive reading toggle that collapses both article sidebars', () => {
    const detail = readSource('views/ArticleDetailView.vue')
    const css = readSource('styles/index.css')

    expect(detail).toContain('const isImmersiveReading = computed')
    expect(detail).toContain('function toggleImmersiveReading()')
    expect(detail).toContain('leftCollapsed.value = next')
    expect(detail).toContain('rightCollapsed.value = next')
    expect(detail).toContain('class="detail-reading-mode-toggle"')
    expect(detail).toContain(':class="{ active: isImmersiveReading }"')
    expect(detail).toContain(':aria-pressed="isImmersiveReading"')
    expect(detail).toContain("{{ isImmersiveReading ? '退出沉浸' : '沉浸阅读' }}")
    expect(detail).toContain('<Contract v-if="isImmersiveReading" />')
    expect(detail).toContain('<Expand v-else />')

    expect(css).toContain('.detail-reading-mode-toggle')
    expect(css).toContain('right: 24px')
    expect(css).toContain('bottom: 188px')
    expect(css).toContain('background: rgba(226, 244, 236, 0.96)')
    expect(css).toContain('background: rgba(209, 239, 227, 0.98)')
    expect(css).toContain('.detail-reading-mode-toggle.active')
  })
})
