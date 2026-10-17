<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useMessage } from 'naive-ui'
import { ArrowBack, Person } from '@vicons/ionicons5'
import { articleApi } from '@/api/article'
import { publicUserApi } from '@/api/publicUser'
import ArticleCard from '@/components/article/ArticleCard.vue'
import EmptyState from '@/components/common/EmptyState.vue'
import { useLoginGuard } from '@/composables/useLoginGuard'
import MainLayout from '@/layouts/MainLayout.vue'
import type { Article } from '@/types/article'
import type { UserRelation } from '@/types/publicUser'

/**
 * 搜索结果页：文章 / 用户两个 tab。
 *
 * 与首页的分工：首页是「浏览信息流」（分类 + 推荐/最新/关注），这里是「检索」。
 * 分开做而不是在首页切 tab，是为了不让 HomeView 同时背两套语义
 * （它已经有 cursor / refresh / authSignature / 搜索态四套状态了）。
 *
 * 两个 tab 的分页方式不同，与后端接口保持一致：
 * - 文章：游标（nextCursor），判断还有没有下一页看它，不看 total
 * - 用户：传统分页（page/pageSize + total）
 */

const route = useRoute()
const router = useRouter()
const message = useMessage()
const { check: requireLogin } = useLoginGuard()

type SearchTabKey = 'article' | 'user'

const searchTabs: Array<{ key: SearchTabKey; label: string }> = [
  { key: 'article', label: '文章' },
  { key: 'user', label: '用户' },
]

const ARTICLE_PAGE_SIZE = 10
const USER_PAGE_SIZE = 10

/** 搜索词来自 header 搜索框，以 query 形式带到本页；也支持直接改 URL */
const keyword = computed(() => String(route.query.keyword ?? '').trim())

function normalizeTab(value: unknown): SearchTabKey {
  return value === 'user' ? 'user' : 'article'
}

/**
 * 当前 tab **完全由 URL 的 type 参数驱动**（computed，不是 ref）。
 *
 * 踩过的坑：用 ref 时，从 /search?keyword=A 切到"用户"tab，再在 header 搜 B
 * 跳到 /search?keyword=B —— 同一个路由、组件被复用，`activeTab` 还留在 'user'，
 * 而 URL 里没有 type=user。于是「界面显示用户、URL 说文章」，刷新后跳回文章，
 * 点"用户"tab 又因为 `activeTab === key` 直接 return 毫无反应。
 */
const activeTab = computed<SearchTabKey>(() => normalizeTab(route.query.type))

/* ---------------- 文章 tab ---------------- */
const articles = ref<Article[]>([])
const articleLoading = ref(false)
const articleLoadingMore = ref(false)
const articleLoadMoreError = ref(false)
const articleHasMore = ref(false)
const articleCursor = ref<string | null>(null)
const articleActionKeys = ref(new Set<string>())
/**
 * 请求序号。换关键词 / 切 tab 时会并发，而响应回来顺序不保证——
 * 旧的 loadMore 若后返回会把上一个关键词的结果追加进当前列表。
 * 只认最新一次请求的响应（与 HomeView 同一套路）。
 */
let articleRequestSeq = 0

/* ---------------- 用户 tab ---------------- */
const users = ref<UserRelation[]>([])
const userLoading = ref(false)
const userLoadingMore = ref(false)
const userLoadMoreError = ref(false)
const userPage = ref(1)
const userTotal = ref(0)
const followActionKeys = ref(new Set<string>())
const userHasMore = computed(() => users.value.length < userTotal.value)
let userRequestSeq = 0

/* ---------------- 加载 ---------------- */

async function loadArticles(reset = false) {
  if (!keyword.value) {
    return
  }

  const seq = ++articleRequestSeq
  if (reset) {
    articleLoading.value = true
  } else {
    articleLoadingMore.value = true
  }
  articleLoadMoreError.value = false

  try {
    const result = await articleApi.getList({
      cursor: reset ? undefined : (articleCursor.value ?? undefined),
      pageSize: ARTICLE_PAGE_SIZE,
      keyword: keyword.value,
      // 刻意不传 sort：有关键词时后端固定走 ES，排序就是相关性。
      // （ES 路径目前没有把 sort 接进排序数组，传了也是假的——见 ArticleSearchService）
    })

    if (seq !== articleRequestSeq) {
      return
    }

    const data = result.data
    const list = data.list ?? []
    articles.value = reset ? list : [...articles.value, ...list]
    articleCursor.value = data.nextCursor ?? null
    articleHasMore.value = Boolean(data.nextCursor)
  } catch (error) {
    if (seq !== articleRequestSeq) {
      return
    }
    if (!reset) {
      articleLoadMoreError.value = true
    }
    message.error(error instanceof Error ? error.message : '文章搜索失败')
  } finally {
    if (seq === articleRequestSeq) {
      articleLoading.value = false
      articleLoadingMore.value = false
    }
  }
}

async function loadUsers(reset = false) {
  if (!keyword.value) {
    return
  }

  const nextPage = reset ? 1 : userPage.value + 1
  const seq = ++userRequestSeq
  if (reset) {
    userLoading.value = true
  } else {
    userLoadingMore.value = true
  }
  userLoadMoreError.value = false

  try {
    const result = await publicUserApi.searchUsers(keyword.value, nextPage, USER_PAGE_SIZE)

    if (seq !== userRequestSeq) {
      return
    }

    const data = result.data
    const list = data.list ?? []
    users.value = reset ? list : [...users.value, ...list]
    userPage.value = data.page ?? nextPage
    userTotal.value = data.total ?? users.value.length
  } catch (error) {
    if (seq !== userRequestSeq) {
      return
    }
    if (!reset) {
      userLoadMoreError.value = true
    }
    message.error(error instanceof Error ? error.message : '用户搜索失败')
  } finally {
    if (seq === userRequestSeq) {
      userLoading.value = false
      userLoadingMore.value = false
    }
  }
}

function loadActiveTab(reset: boolean) {
  return activeTab.value === 'article' ? loadArticles(reset) : loadUsers(reset)
}

/* ---------------- tab 与 URL ---------------- */

function switchTab(key: SearchTabKey) {
  if (activeTab.value === key) {
    return
  }

  // 只改 URL，加载由 watch(activeTab) 触发——URL 是单一数据源
  // tab 写进 URL 的另一个好处：刷新 / 分享后还停在同一栏
  void router.replace({ query: { ...route.query, type: key } })
}

function resetLists() {
  articles.value = []
  articleCursor.value = null
  articleHasMore.value = false
  articleLoadMoreError.value = false
  articleLoading.value = false
  articleLoadingMore.value = false

  users.value = []
  userPage.value = 1
  userTotal.value = 0
  userLoadMoreError.value = false
  userLoading.value = false
  userLoadingMore.value = false
}

onMounted(() => {
  if (keyword.value) {
    void loadActiveTab(true)
  }
})

// header 搜索框再次提交时只改 query、不重建组件，所以这里要跟着重载
watch(keyword, () => {
  resetLists()
  if (keyword.value) {
    void loadActiveTab(true)
  }
})

// tab 切换（URL 的 type 变化）。切回来时若已有结果就直接复用，不重复请求
watch(activeTab, () => {
  if (!keyword.value) {
    return
  }
  const currentList = activeTab.value === 'article' ? articles.value : users.value
  if (currentList.length) {
    return
  }
  void loadActiveTab(true)
})

/* ---------------- 文章交互（与首页同一套口径） ---------------- */

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
  if (!requireLogin()) {
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
    message.error(error instanceof Error ? error.message : '操作失败')
  } finally {
    finishArticleAction(actionKey)
  }
}

async function handleArticleFavorite(article: Article) {
  if (!requireLogin()) {
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
    message.error(error instanceof Error ? error.message : '操作失败')
  } finally {
    finishArticleAction(actionKey)
  }
}

/* ---------------- 用户交互 ---------------- */

function beginFollowAction(key: string) {
  if (followActionKeys.value.has(key)) {
    return false
  }
  followActionKeys.value = new Set([...followActionKeys.value, key])
  return true
}

function finishFollowAction(key: string) {
  const nextKeys = new Set(followActionKeys.value)
  nextKeys.delete(key)
  followActionKeys.value = nextKeys
}

async function handleFollowToggle(user: UserRelation) {
  if (user.self) {
    return
  }
  if (!requireLogin()) {
    return
  }

  const actionKey = `follow-${user.id}`
  if (!beginFollowAction(actionKey)) {
    return
  }

  try {
    if (user.followed) {
      await publicUserApi.cancelFollow(user.id)
      user.followed = false
    } else {
      await publicUserApi.follow(user.id)
      user.followed = true
    }
  } catch (error) {
    message.error(error instanceof Error ? error.message : '操作失败')
  } finally {
    finishFollowAction(actionKey)
  }
}

function goToArticle(article: Article) {
  router.push(`/articles/${article.id}`)
}

function goToUser(user: UserRelation) {
  router.push(`/users/${user.id}`)
}

/**
 * 返回上一页。
 *
 * 直接打开 /search（地址栏 / 新标签页）时没有站内上一页，
 * `router.back()` 会把用户带出站点——那种情况兜底回首页。
 */
function goBack() {
  if (window.history.state?.back) {
    router.back()
    return
  }
  router.push('/')
}
</script>

<template>
  <MainLayout>
    <div class="search-page">
      <button class="search-back" type="button" @click="goBack">
        <n-icon><ArrowBack /></n-icon>
        <span>返回</span>
      </button>

      <div class="search-tabs" role="tablist">
        <button
          v-for="tab in searchTabs"
          :key="tab.key"
          type="button"
          role="tab"
          :aria-selected="tab.key === activeTab"
          :class="{ active: tab.key === activeTab }"
          @click="switchTab(tab.key)"
        >
          {{ tab.label }}
        </button>
      </div>

      <!-- 没有关键词：不展示任何列表，避免被误解成「搜到了全站内容」 -->
      <EmptyState
        v-if="!keyword"
        title="输入关键词开始搜索"
        description="文章按标题 / 摘要 / 正文检索，用户按昵称检索。"
      />

      <!-- 文章结果 -->
      <section v-else-if="activeTab === 'article'" class="search-panel">
        <div v-if="articleLoading && !articles.length" class="search-skeletons">
          <div v-for="item in 4" :key="item" class="search-skeleton-row">
            <n-skeleton text style="width: 48%; height: 21px; margin-bottom: 8px" />
            <n-skeleton text style="margin-bottom: 8px" />
            <n-skeleton text style="width: 72%" />
          </div>
        </div>

        <div v-else-if="articles.length" class="search-article-list">
          <ArticleCard
            v-for="article in articles"
            :key="article.id"
            :article="article"
            :keyword="keyword"
            @open="goToArticle"
            @like="handleArticleLike"
            @favorite="handleArticleFavorite"
            @author-click="router.push(`/users/${$event}`)"
          />
        </div>

        <EmptyState
          v-else
          title="没有找到相关文章"
          description="换个关键词试试，或者检查有没有错别字。"
        />

        <div v-if="articles.length" class="search-load-more">
          <button
            v-if="articleLoadMoreError"
            type="button"
            class="search-load-more-btn"
            @click="loadArticles(false)"
          >
            加载失败，点击重试
          </button>
          <button
            v-else-if="articleHasMore"
            type="button"
            class="search-load-more-btn"
            :disabled="articleLoadingMore"
            @click="loadArticles(false)"
          >
            {{ articleLoadingMore ? '正在加载…' : '加载更多' }}
          </button>
          <span v-else class="search-end">已经到底啦</span>
        </div>
      </section>

      <!-- 用户结果 -->
      <section v-else class="search-panel">
        <div v-if="userLoading && !users.length" class="search-skeletons">
          <div v-for="item in 3" :key="item" class="search-skeleton-row search-skeleton-user">
            <n-skeleton circle style="width: 58px; height: 58px" />
            <div style="flex: 1">
              <n-skeleton text style="width: 32%; height: 20px; margin-bottom: 8px" />
              <n-skeleton text style="width: 56%" />
            </div>
          </div>
        </div>

        <div v-else-if="users.length" class="search-user-list">
          <div
            v-for="user in users"
            :key="user.id"
            class="search-user-row"
            role="button"
            tabindex="0"
            @click="goToUser(user)"
            @keydown.enter="goToUser(user)"
          >
            <n-avatar :size="58" :src="user.avatarUrl" class="search-user-avatar">
              <template #fallback>
                <n-icon><Person /></n-icon>
              </template>
            </n-avatar>

            <div class="search-user-info">
              <h3>{{ user.nickname || `#${user.id}` }}</h3>
              <p>{{ user.bio || '这个人还没有写简介。' }}</p>
            </div>

            <n-button
              v-if="!user.self"
              type="primary"
              :secondary="user.followed"
              :loading="followActionKeys.has(`follow-${user.id}`)"
              @click.stop="handleFollowToggle(user)"
            >
              {{ user.followed ? '已关注' : '关注' }}
            </n-button>
          </div>
        </div>

        <EmptyState
          v-else
          title="没有找到相关用户"
          description="换个关键词试试，或者检查昵称有没有写全。"
        />

        <div v-if="users.length" class="search-load-more">
          <button
            v-if="userLoadMoreError"
            type="button"
            class="search-load-more-btn"
            @click="loadUsers(false)"
          >
            加载失败，点击重试
          </button>
          <button
            v-else-if="userHasMore"
            type="button"
            class="search-load-more-btn"
            :disabled="userLoadingMore"
            @click="loadUsers(false)"
          >
            {{ userLoadingMore ? '正在加载…' : '加载更多' }}
          </button>
          <span v-else class="search-end">已经到底啦</span>
        </div>
      </section>
    </div>
  </MainLayout>
</template>

<style scoped>
.search-page {
  display: grid;
  gap: 0;
  max-width: 860px;
  margin: 0 auto;
  padding: 28px 0 64px;
}

.search-back {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  margin: 0 28px 18px;
  padding: 0;
  border: 0;
  background: transparent;
  color: var(--color-forest);
  font: inherit;
  font-weight: 700;
  cursor: pointer;
}

.search-back:hover {
  color: #245659;
}

.search-back:focus-visible {
  outline: 2px solid rgba(47, 111, 115, 0.36);
  outline-offset: 2px;
}

.search-tabs {
  display: flex;
  gap: 6px;
  padding: 0 28px;
  border-bottom: 1px solid #eef0f2;
}

.search-tabs button {
  padding: 12px 20px;
  border: none;
  background: transparent;
  color: #6b7280;
  font-size: 15px;
  cursor: pointer;
  border-bottom: 2px solid transparent;
  transition: color 0.16s;
}

.search-tabs button:hover {
  color: #111827;
}

.search-tabs button.active {
  color: var(--color-forest);
  border-bottom-color: var(--color-forest);
  font-weight: 600;
}

.search-tabs button:focus-visible {
  outline: 2px solid rgba(47, 111, 115, 0.36);
  outline-offset: -2px;
}

.search-panel {
  min-height: 240px;
}

.search-article-list {
  display: grid;
}

.search-skeletons {
  display: grid;
}

.search-skeleton-row {
  padding: 22px 28px;
  border-bottom: 1px solid #eef0f2;
}

.search-skeleton-user {
  display: flex;
  gap: 22px;
  align-items: center;
}

.search-user-list {
  display: grid;
}

.search-user-row {
  display: grid;
  grid-template-columns: 58px minmax(0, 1fr) 112px;
  align-items: center;
  gap: 22px;
  min-height: 112px;
  padding: 22px 28px;
  border-bottom: 1px solid #eef0f2;
  cursor: pointer;
  transition: background 0.16s;
}

.search-user-row:hover {
  background: #fbfdfc;
}

.search-user-row:focus-visible {
  outline: 2px solid rgba(47, 111, 115, 0.36);
  outline-offset: -2px;
}

.search-user-info {
  min-width: 0;
}

.search-user-info h3 {
  margin: 0 0 8px;
  overflow: hidden;
  color: #111827;
  font-size: 18px;
  line-height: 1.35;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.search-user-info p {
  margin: 0;
  overflow: hidden;
  color: #6b7280;
  font-size: 14px;
  line-height: 1.55;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.search-load-more {
  display: flex;
  justify-content: center;
  padding: 28px;
}

.search-load-more-btn {
  padding: 9px 22px;
  border: 1px solid #dfe3e6;
  border-radius: 999px;
  background: #fff;
  color: #374151;
  font-size: 14px;
  cursor: pointer;
  transition:
    border-color 0.16s,
    color 0.16s;
}

.search-load-more-btn:hover:not(:disabled) {
  border-color: var(--color-forest);
  color: var(--color-forest);
}

.search-load-more-btn:disabled {
  cursor: default;
  opacity: 0.6;
}

.search-end {
  color: #9aa3ab;
  font-size: 13px;
}

@media (max-width: 720px) {
  .search-page {
    padding: 18px 0 48px;
  }

  .search-tabs {
    padding-left: 16px;
    padding-right: 16px;
  }

  .search-back {
    margin-left: 16px;
    margin-right: 16px;
  }

  .search-user-row {
    grid-template-columns: 48px minmax(0, 1fr) 92px;
    gap: 14px;
    padding: 18px 16px;
  }

  .search-tabs button {
    padding: 10px 14px;
  }
}
</style>
