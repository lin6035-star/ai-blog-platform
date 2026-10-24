<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import ArticleCard from '@/components/article/ArticleCard.vue'
import EmptyState from '@/components/common/EmptyState.vue'
import type { ArticleSort } from '@/api/article'
import type { Article } from '@/types/article'

const props = defineProps<{
  articles: Article[]
  hasMore: boolean
  loading: boolean
  loadingMore: boolean
  loadMoreError: boolean
  sort: ArticleSort
  /** 「关注」流的空状态要区分「没登录」和「登录了但没关注」——文案和按钮都不一样 */
  isLoggedIn: boolean
  /** 当前搜索关键词。透传给卡片，用于 ES 降级到 LIKE 时的兜底高亮 */
  keyword?: string
}>()

const emit = defineEmits<{
  open: [article: Article]
  'sort-change': [sort: ArticleSort]
  loadMore: []
  like: [article: Article]
  favorite: [article: Article]
  'author-click': [authorId: string]
}>()

const sortTabs: Array<{ label: string; value: ArticleSort }> = [
  { label: '推荐', value: 'recommend' },
  { label: '最新', value: 'latest' },
  { label: '关注', value: 'following' },
]

const emptyTitle = computed(() =>
  props.sort === 'following' ? '暂无关注人发布的文章' : '暂无公开文章',
)

const emptyDescription = computed(() => {
  if (props.sort === 'following') {
    return props.isLoggedIn
      ? '去关注几位作者吧，他们发布的新文章会出现在这里。'
      : '登录后就能看到你关注的作者发布的文章。'
  }
  return '等后端准备好文章数据后，这里会显示技术信息流。'
})

const loadMoreTriggerRef = ref<HTMLElement | null>(null)
let observer: IntersectionObserver | null = null

function canLoadMore() {
  return props.hasMore && !props.loading && !props.loadingMore && !props.loadMoreError
}

function handleLoadMoreIntersect(entries: IntersectionObserverEntry[]) {
  const isVisible = entries.some((entry) => entry.isIntersecting)

  if (isVisible && canLoadMore()) {
    emit('loadMore')
  }
}

function setupLoadMoreObserver() {
  if (typeof IntersectionObserver === 'undefined' || !loadMoreTriggerRef.value) {
    return
  }

  observer?.disconnect()
  observer = new IntersectionObserver(handleLoadMoreIntersect, {
    rootMargin: '160px 0px',
  })
  observer.observe(loadMoreTriggerRef.value)
}

onMounted(setupLoadMoreObserver)

onBeforeUnmount(() => {
  observer?.disconnect()
})

watch(
  () => [props.hasMore, props.loading, props.loadingMore, props.articles.length],
  setupLoadMoreObserver,
  { flush: 'post' },
)
</script>

<template>
  <section class="feed-panel">
    <div class="feed-tabs">
      <button
        v-for="tab in sortTabs"
        :key="tab.value"
        :class="{ active: tab.value === sort }"
        type="button"
        @click="emit('sort-change', tab.value)"
      >
        {{ tab.label }}
      </button>
    </div>

    <div v-if="loading && !articles.length" class="article-list">
      <div v-for="item in 4" :key="item" style="padding: 20px">
        <n-skeleton text style="width: 48%; height: 21px; margin-bottom: 8px" />
        <n-skeleton text style="margin-bottom: 8px" />
        <n-skeleton text style="width: 72%" />
      </div>
    </div>

    <div v-else-if="articles.length" class="article-list">
      <ArticleCard
        v-for="article in articles"
        :key="article.id"
        :article="article"
        :keyword="keyword"
        @open="emit('open', $event)"
        @like="emit('like', $event)"
        @favorite="emit('favorite', $event)"
        @author-click="emit('author-click', $event)"
      />
    </div>

    <EmptyState v-else :title="emptyTitle" :description="emptyDescription">
      <!-- 用 router-link 而不是 @click + router.push：保持链接语义（可右键、可新窗口），
           也和全站「未登录操作就地提示、不命令式跳转」的约定一致 -->
      <router-link
        v-if="sort === 'following' && !isLoggedIn"
        class="empty-action"
        to="/login"
      >
        立即登录
      </router-link>
    </EmptyState>

    <div
      v-if="articles.length || loadingMore || loadMoreError"
      ref="loadMoreTriggerRef"
      class="feed-load-more-trigger"
      aria-live="polite"
    >
      <button
        v-if="loadMoreError"
        class="feed-load-retry"
        type="button"
        @click="emit('loadMore')"
      >
        加载失败，点击重试
      </button>
      <span v-else-if="loadingMore">正在加载更多...</span>
      <span v-else-if="hasMore">继续下滑加载更多</span>
      <span v-else>已经到底啦</span>
    </div>
  </section>
</template>

<style scoped>
.empty-action {
  margin-top: 18px;
  border: 1px solid var(--color-forest);
  border-radius: 8px;
  padding: 9px 20px;
  color: white;
  background: var(--color-forest);
  cursor: pointer;
  font-weight: 800;
  transition:
    filter 0.18s ease,
    transform 0.18s ease;
}

.empty-action:hover {
  filter: brightness(1.08);
  transform: translateY(-1px);
}

.feed-load-more-trigger {
  display: flex;
  justify-content: center;
  align-items: center;
  min-height: 58px;
  padding: 24px 20px 20px;
  border-top: 1px solid var(--color-border);
  color: var(--color-muted);
  font-size: 14px;
}

.feed-load-retry {
  border: 1px solid var(--color-border);
  border-radius: 8px;
  padding: 8px 14px;
  color: var(--color-forest);
  background: white;
  cursor: pointer;
  font-weight: 800;
  transition:
    border-color 0.18s ease,
    background 0.18s ease,
    transform 0.18s ease;
}

.feed-load-retry:hover {
  border-color: var(--color-forest);
  background: var(--color-mint);
  transform: translateY(-1px);
}
</style>
