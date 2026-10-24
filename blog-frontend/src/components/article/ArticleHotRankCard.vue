<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useMessage } from 'naive-ui'
import { articleApi } from '@/api/article'
import type { Article } from '@/types/article'
import type { PageData } from '@/types/result'

const router = useRouter()
const message = useMessage()
const articles = ref<Article[]>([])
const loading = ref(false)

async function loadHotArticles() {
  loading.value = true

  try {
    const result = await articleApi.getHotList({ page: 1, pageSize: 5 })
    const data = result.data as PageData<Article>
    articles.value = data.list ?? []
  } catch (error) {
    const msg = error instanceof Error ? error.message : '热度榜加载失败'
    message.error(msg)
  } finally {
    loading.value = false
  }
}

function openMore() {
  router.push({ name: 'hot-rank' })
}

onMounted(loadHotArticles)
</script>

<template>
  <section class="side-card article-hot-rank-card" aria-label="热度榜">
    <div class="hot-rank-card-header">
      <h2>热度榜</h2>
    </div>

    <div v-if="loading" class="hot-rank-loading">
      <n-skeleton v-for="item in 5" :key="item" text style="height: 22px" />
    </div>

    <ol v-else-if="articles.length" class="hot-rank-list">
      <li v-for="(article, index) in articles" :key="article.id">
        <router-link :to="`/articles/${article.id}`">
          <span class="hot-rank-index">{{ index + 1 }}</span>
          <span class="hot-rank-title">{{ article.title }}</span>
        </router-link>
      </li>
    </ol>

    <p v-else class="hot-rank-empty">暂无热度文章</p>

    <button class="hot-rank-more" type="button" @click="openMore">
      点击展开更多
    </button>
  </section>
</template>

<style scoped>
.article-hot-rank-card {
  display: grid;
  gap: 18px;
}

.hot-rank-card-header h2 {
  margin-bottom: 0;
  /* 与左侧分类栏的「分类」标题对齐：标题居中，两侧栏视觉上对称 */
  text-align: center;
}

.hot-rank-loading {
  display: grid;
  gap: 12px;
}

.hot-rank-list {
  display: grid;
  gap: 3px;
  margin: 0;
  padding: 0;
  list-style: none;
}

.hot-rank-list a {
  display: grid;
  grid-template-columns: 24px minmax(0, 1fr);
  gap: 8px;
  align-items: center;
  min-height: 34px;
  border-radius: 8px;
  color: var(--color-text);
  transition:
    background 0.18s ease,
    color 0.18s ease,
    transform 0.18s ease;
}

.hot-rank-list a:hover {
  color: var(--color-forest);
  background: var(--color-mint);
  transform: translateX(2px);
}

.hot-rank-index {
  color: var(--color-coral);
  font-size: 15px;
  font-weight: 900;
  text-align: center;
}

.hot-rank-title {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-weight: 700;
}

.hot-rank-empty {
  margin: 0;
  color: var(--color-muted);
  font-size: 14px;
}

.hot-rank-more {
  width: 100%;
  border: 1px solid var(--color-border);
  border-radius: 8px;
  padding: 8px 10px;
  color: var(--color-forest);
  background: white;
  cursor: pointer;
  font-weight: 800;
  transition:
    border-color 0.18s ease,
    background 0.18s ease,
    transform 0.18s ease;
}

.hot-rank-more:hover {
  border-color: var(--color-forest);
  background: var(--color-mint);
  transform: translateY(-1px);
}
</style>
