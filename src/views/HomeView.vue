<script setup lang="ts">
import { computed, onActivated, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useMessage } from 'naive-ui'
import { articleApi } from '@/api/article'
import { categoryApi } from '@/api/category'
import ArticleFeed from '@/components/article/ArticleFeed.vue'
import ArticleHotRankCard from '@/components/article/ArticleHotRankCard.vue'
import MainLayout from '@/layouts/MainLayout.vue'
import { useAuthStore } from '@/stores/auth'
import type { Article } from '@/types/article'
import type { Category } from '@/types/category'
import type { PageData } from '@/types/result'
import type { ArticleSort } from '@/api/article'

defineOptions({ name: 'HomeView' })

const route = useRoute()
const router = useRouter()
const message = useMessage()
const authStore = useAuthStore()
const articles = ref<Article[]>([])
const categories = ref<Category[]>([])
const loading = ref(false)
const loadingMore = ref(false)
const loadMoreError = ref(false)
const selectedCategoryId = ref('0')
const selectedSort = ref<ArticleSort>('recommend')
const articleActionKeys = ref(new Set<string>())

/* ---- 分页（游标 / keyset，不是页码） ---- */
const pageSize = 10
/** 下一页游标。null = 从头开始；后端返回 null = 已经到底 */
const cursor = ref<string | null>(null)
/** 还有没有下一页。**由后端返回的 nextCursor 驱动，不看 total**——游标分页不算 total */
const hasMore = ref(false)
/**
 * 请求序号。切换分类 / 排序时会并发出两条请求，而响应回来的顺序不保证——
 * 旧的 loadMore 若后返回，会把它那个分类的数据追加进新分类的列表，还会把游标
 * 覆盖成旧分类的位置（下次 loadMore 从错误位置继续，中间那段被跳过）。
 * 只认最新一次请求的响应。
 */
let loadRequestSeq = 0

const searchKeyword = computed(() => (route.query.keyword as string) ?? '')
const isHomeRoute = computed(() => route.name === 'home')
const authSignature = computed(() =>
  authStore.isLoggedIn && authStore.usersVO ? `user:${authStore.usersVO.id}` : 'guest',
)
const loadedAuthSignature = ref('')

const allCategory: Category = { id: '0', name: '综合', code: 'all', description: '全部内容' }

const fallbackCategories: Category[] = [
  { id: '1', name: 'AI / Agent', code: 'ai-agent', description: 'AI、Agent、coding agent 相关内容' },
  { id: '2', name: 'Java 后端', code: 'java-backend', description: 'Java、Spring Boot、后端开发相关内容' },
  { id: '3', name: '项目实战', code: 'project-practice', description: '项目开发、部署、踩坑记录' },
  { id: '4', name: '随笔', code: 'notes', description: '临时想法、学习记录、杂谈' },
]

const categoryItems = computed(() =>
  [allCategory, ...(categories.value.length ? categories.value : fallbackCategories)],
)

async function loadArticles(reset = false) {
  const isFirstPage = reset
  const seq = ++loadRequestSeq

  if (isFirstPage) {
    loading.value = true
  } else {
    loadingMore.value = true
  }
  loadMoreError.value = false

  try {
    const result = await articleApi.getList({
      // 第一页不带游标；否则原样带回上一次的 nextCursor（不要在前端拼它）
      cursor: isFirstPage ? undefined : (cursor.value ?? undefined),
      pageSize,
      keyword: searchKeyword.value || undefined,
      categoryId: selectedCategoryId.value === allCategory.id ? undefined : selectedCategoryId.value,
      sort: selectedSort.value,
    })

    if (seq !== loadRequestSeq) {
      return
    }

    const data = result.data as PageData<Article>
    const list = data.list ?? []
    articles.value = isFirstPage ? list : [...articles.value, ...list]
    cursor.value = data.nextCursor ?? null
    hasMore.value = Boolean(data.nextCursor)
  } catch (error) {
    if (seq !== loadRequestSeq) {
      return
    }

    if (!isFirstPage) {
      loadMoreError.value = true
    }
    const msg = error instanceof Error ? error.message : '文章加载失败'
    message.error(msg)
  } finally {
    // 过期请求不重置这两个状态：最新的那次请求还在飞，状态归它管
    if (seq === loadRequestSeq) {
      loading.value = false
      loadingMore.value = false
    }
  }
}

async function loadHomeData() {
  try {
    const categoryResult = await categoryApi.getList()
    categories.value = categoryResult.data ?? []
  } catch {
    // 分类加载失败时使用 fallback，不阻断页面
  }

  await loadArticles(true)
  loadedAuthSignature.value = authSignature.value
}

async function refreshArticlesForAuthChange() {
  if (!isHomeRoute.value || loadedAuthSignature.value === authSignature.value) {
    return
  }

  resetCursor()
  await loadArticles(true)
  loadedAuthSignature.value = authSignature.value
}

/** 回到「第一页」：清空游标与"到底"标记。列表本身由各调用方决定要不要一起清 */
function resetCursor() {
  cursor.value = null
  hasMore.value = false
}

function resetHomeState() {
  selectedCategoryId.value = allCategory.id
  selectedSort.value = 'recommend'
  resetCursor()
  articles.value = []
  loadMoreError.value = false
}

function reloadArticles() {
  resetCursor()
  articles.value = []
  loadMoreError.value = false
  void loadArticles(true)
}

function loadMoreArticles() {
  if (loading.value || loadingMore.value || !hasMore.value) {
    return
  }

  void loadArticles(false)
}

function onCategorySelect(category: Category) {
  selectedCategoryId.value = category.id
  reloadArticles()
}

function onSortChange(sort: ArticleSort) {
  selectedSort.value = sort
  reloadArticles()
}

function beginArticleAction(key: string) {
  if (articleActionKeys.value.has(key)) {
    return false
  }

  articleActionKeys.value = new Set([...articleActionKeys.value, key])
  return true
}

function finishArticleAction(key: string) {
  const nextKeys = new Set(articleActionKeys.value)
  nextKeys.delete(key)
  articleActionKeys.value = nextKeys
}

async function handleArticleLike(article: Article) {
  if (!authStore.isLoggedIn) {
    message.warning('请先登录后再操作亲')
    return
  }

  const actionKey = `like-${article.id}`
  if (!beginArticleAction(actionKey)) {
    return
  }

  const wasLiked = article.liked === 1
  try {
    if (wasLiked) {
      await articleApi.unlike(article.id)
      article.liked = 0
      article.likeCount = Math.max(0, (article.likeCount || 0) - 1)
    } else {
      await articleApi.like(article.id)
      article.liked = 1
      article.likeCount = (article.likeCount || 0) + 1
    }
  } catch (error) {
    const msg = error instanceof Error ? error.message : '操作失败'
    message.error(msg)
  } finally {
    finishArticleAction(actionKey)
  }
}

async function handleArticleFavorite(article: Article) {
  if (!authStore.isLoggedIn) {
    message.warning('请先登录后再操作亲')
    return
  }

  const actionKey = `favorite-${article.id}`
  if (!beginArticleAction(actionKey)) {
    return
  }

  const wasFavorited = article.favorited === 1
  try {
    if (wasFavorited) {
      await articleApi.unfavorite(article.id)
      article.favorited = 0
      article.favoriteCount = Math.max(0, (article.favoriteCount || 0) - 1)
    } else {
      await articleApi.favorite(article.id)
      article.favorited = 1
      article.favoriteCount = (article.favoriteCount || 0) + 1
    }
  } catch (error) {
    const msg = error instanceof Error ? error.message : '操作失败'
    message.error(msg)
  } finally {
    finishArticleAction(actionKey)
  }
}

function goToAuthorProfile(authorId: string) {
  router.push(`/users/${authorId}`)
}

function goToArticle(article: Article) {
  router.push(`/articles/${article.id}`)
}

onMounted(loadHomeData)

onActivated(refreshArticlesForAuthChange)

watch(authSignature, refreshArticlesForAuthChange)

watch(
  () => route.query.keyword,
  () => {
    if (!isHomeRoute.value || route.query.refresh) {
      return
    }

    // reloadArticles 内部会重置游标与"到底"标记，这里不需要再单独重置
    reloadArticles()
  },
)

watch(
  () => route.query.refresh,
  async () => {
    if (!isHomeRoute.value || !route.query.refresh) {
      return
    }

    resetHomeState()
    await loadHomeData()
    window.scrollTo({ top: 0, left: 0 })
    const cleanQuery = { ...route.query }
    delete cleanQuery.refresh
    await router.replace({ path: '/', query: cleanQuery })
  },
)
</script>

<template>
  <MainLayout>
    <div class="feed-layout home-feed-layout">
      <aside class="category-rail" aria-label="分类导航">
        <div class="category-rail-title">分类</div>
        <button
          v-for="category in categoryItems"
          :key="category.id"
          :class="{ active: category.id === selectedCategoryId }"
          type="button"
          @click="onCategorySelect(category)"
        >
          <span class="category-icon">{{ category.name.slice(0, 1) }}</span>
          <span>{{ category.name }}</span>
        </button>
      </aside>

      <ArticleFeed
        class="main-feed"
        :articles="articles"
        :has-more="hasMore"
        :loading="loading"
        :loading-more="loadingMore"
        :load-more-error="loadMoreError"
        :sort="selectedSort"
        :is-logged-in="authStore.isLoggedIn"
        :keyword="searchKeyword"
        @open="goToArticle"
        @like="handleArticleLike"
        @favorite="handleArticleFavorite"
        @author-click="goToAuthorProfile"
        @load-more="loadMoreArticles"
        @sort-change="onSortChange"
      />

      <aside class="home-side-rail" aria-label="首页侧边栏">
        <ArticleHotRankCard />
      </aside>
    </div>
  </MainLayout>
</template>
