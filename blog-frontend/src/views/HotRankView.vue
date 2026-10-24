<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useMessage } from 'naive-ui'
import { ArrowBack } from '@vicons/ionicons5'
import { articleApi } from '@/api/article'
import MainLayout from '@/layouts/MainLayout.vue'
import type { Article } from '@/types/article'
import type { PageData } from '@/types/result'

const router = useRouter()
const message = useMessage()
const articles = ref<Article[]>([])
const loading = ref(false)
const currentPage = ref(1)
const pageSize = 10
const totalArticles = ref(0)
const backBarRef = ref<HTMLElement | null>(null)
const isBackBarStuck = ref(false)

async function loadHotArticles() {
  loading.value = true

  try {
    const result = await articleApi.getHotList({
      page: currentPage.value,
      pageSize,
    })
    const data = result.data as PageData<Article>
    articles.value = data.list ?? []
    totalArticles.value = data.total ?? 0
  } catch (error) {
    const msg = error instanceof Error ? error.message : '热度榜加载失败'
    message.error(msg)
  } finally {
    loading.value = false
  }
}

function onPageChange(page: number) {
  currentPage.value = page
  loadHotArticles()
  window.scrollTo({ top: 0, left: 0 })
}

function rankNumber(index: number) {
  return (currentPage.value - 1) * pageSize + index + 1
}

function goBack() {
  if (window.history.state?.back) {
    router.back()
    return
  }
  router.push({ path: '/', query: { refresh: String(Date.now()) } })
}

function updateBackBarStuck() {
  const bar = backBarRef.value
  if (!bar) {
    isBackBarStuck.value = false
    return
  }
  isBackBarStuck.value = window.scrollY > 0 && bar.getBoundingClientRect().top <= 64
}

onMounted(() => {
  loadHotArticles()
  nextTick(updateBackBarStuck)
  window.addEventListener('scroll', updateBackBarStuck, { passive: true })
  window.addEventListener('resize', updateBackBarStuck)
})

onBeforeUnmount(() => {
  window.removeEventListener('scroll', updateBackBarStuck)
  window.removeEventListener('resize', updateBackBarStuck)
})
</script>

<template>
  <MainLayout>
    <div
      ref="backBarRef"
      class="detail-back-bar"
      :class="{ stuck: isBackBarStuck }"
    >
      <button class="back-link" type="button" @click="goBack">
        <n-icon><ArrowBack /></n-icon>
        返回
      </button>
    </div>

    <section class="hot-rank-page">
      <header class="hot-rank-page-header">
        <p class="eyebrow">HOT RANK</p>
        <h1>热度榜</h1>
      </header>

      <div class="hot-rank-panel">
        <div v-if="loading" class="hot-rank-page-loading">
          <div v-for="item in 6" :key="item" class="hot-rank-skeleton">
            <n-skeleton text style="width: 42%; height: 24px" />
            <n-skeleton text style="width: 58%" />
          </div>
        </div>

        <ol v-else-if="articles.length" class="hot-rank-page-list">
          <li v-for="(article, index) in articles" :key="article.id" class="hot-rank-page-item">
            <span class="hot-rank-page-index">{{ rankNumber(index) }}</span>
            <router-link class="hot-rank-page-main" :to="`/articles/${article.id}`">
              <h2>{{ article.title }}</h2>
              <div class="hot-rank-page-meta">
                <span>{{ article.authorName || `#${article.authorId}` }}</span>
                <span>{{ article.viewCount || 0 }} 浏览</span>
                <span>{{ article.commentCount || 0 }} 评论</span>
                <span>{{ article.favoriteCount || 0 }} 收藏</span>
              </div>
            </router-link>
          </li>
        </ol>

        <div v-else class="empty-state">
          <div class="empty-mark">榜</div>
          <h2>暂无热度文章</h2>
          <p>访问、点赞、收藏或评论文章后，热度榜会开始有数据。</p>
        </div>

        <div v-if="totalArticles > pageSize" class="feed-pagination">
          <n-pagination
            :page="currentPage"
            :page-size="pageSize"
            :item-count="totalArticles"
            @update:page="onPageChange"
          />
        </div>
      </div>
    </section>
  </MainLayout>
</template>

<style scoped>
.hot-rank-page {
  display: grid;
  gap: 18px;
}

.hot-rank-page-header {
  padding: 10px 2px 0;
}

.hot-rank-page-header h1 {
  margin: 0;
  color: var(--color-ink);
  font-size: 34px;
  line-height: 1.2;
}

.hot-rank-panel {
  overflow: hidden;
  border: 1px solid var(--color-border);
  border-radius: 8px;
  background: var(--color-surface);
  box-shadow: var(--shadow-soft);
}

.hot-rank-page-loading {
  display: grid;
  gap: 1px;
}

.hot-rank-skeleton {
  display: grid;
  gap: 10px;
  padding: 24px;
  background: white;
}

.hot-rank-page-list {
  margin: 0;
  padding: 0;
  list-style: none;
}

.hot-rank-page-item {
  display: grid;
  grid-template-columns: 46px minmax(0, 1fr);
  gap: 18px;
  align-items: center;
  padding: 22px 24px;
  border-bottom: 1px solid var(--color-border);
}

.hot-rank-page-item:nth-child(3n) {
  background: #f8fbfb;
}

.hot-rank-page-index {
  color: var(--color-coral);
  font-size: 24px;
  font-weight: 900;
  text-align: center;
}

.hot-rank-page-main {
  min-width: 0;
}

.hot-rank-page-main h2 {
  margin: 0 0 10px;
  overflow: hidden;
  color: var(--color-ink);
  font-size: 20px;
  line-height: 1.35;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.hot-rank-page-main:hover h2 {
  color: var(--color-forest);
}

.hot-rank-page-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  color: var(--color-muted);
  font-size: 14px;
}

@media (max-width: 700px) {
  .hot-rank-page-item {
    grid-template-columns: 34px minmax(0, 1fr);
    gap: 12px;
    padding: 18px 16px;
  }

  .hot-rank-page-index {
    font-size: 20px;
  }
}
</style>
