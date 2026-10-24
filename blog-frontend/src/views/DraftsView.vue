<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useDialog, useMessage } from 'naive-ui'
import { ArrowBack, CreateOutline, TrashOutline } from '@vicons/ionicons5'
import MainLayout from '@/layouts/MainLayout.vue'
import { myArticleApi } from '@/api/myArticle'
import { ARTICLE_STATUS } from '@/constants/articleStatus'
import type { Article } from '@/types/article'
import { formatArticleDateTime } from '@/utils/format'

const router = useRouter()
const message = useMessage()
const dialog = useDialog()

/* ---- 列表状态 ---- */
const loading = ref(false)
const articles = ref<Article[]>([])
const currentPage = ref(1)
const pageSize = 10
const total = ref(0)

/* ---- 加载 ---- */
async function loadDrafts() {
  loading.value = true
  try {
    const res = await myArticleApi.getList(currentPage.value, pageSize, ARTICLE_STATUS.DRAFT)
    const data = res.data
    articles.value = data.list ?? []
    total.value = data.total ?? 0
  } catch {
    message.error('草稿加载失败')
  } finally {
    loading.value = false
  }
}

/* ---- 操作 ---- */
function handleEdit(article: Article) {
  router.push(`/editor/${article.id}`)
}

function handlePublish(article: Article) {
  dialog.warning({
    title: '确认发布',
    content: `确定要发布《${article.title}》吗？发布后将对所有人可见。`,
    positiveText: '发布',
    negativeText: '取消',
    onPositiveClick: async () => {
      try {
        await myArticleApi.publish(article.id)
        message.success('已发布')
        loadDrafts()
      } catch {
        message.error('发布失败')
      }
    },
  })
}

function handleDelete(article: Article) {
  dialog.error({
    title: '确认删除',
    content: `确定要删除《${article.title}》吗？删除后无法恢复。`,
    positiveText: '删除',
    negativeText: '取消',
    onPositiveClick: async () => {
      try {
        await myArticleApi.delete(article.id)
        message.success('已删除')
        loadDrafts()
      } catch {
        message.error('删除失败')
      }
    },
  })
}

/* ---- 分页 ---- */
function onPageChange(page: number) {
  currentPage.value = page
  loadDrafts()
}

onMounted(loadDrafts)
</script>

<template>
  <MainLayout>
    <div class="drafts-page">
    <!-- 顶部 -->
    <header class="drafts-header">
      <button class="drafts-back" type="button" @click="router.back()">
        <n-icon size="20"><ArrowBack /></n-icon>
      </button>
      <h1>草稿箱</h1>
      <span v-if="total" class="drafts-count">共 {{ total }} 篇</span>
    </header>

    <!-- 加载骨架 -->
    <div v-if="loading" class="drafts-list">
      <div v-for="item in 3" :key="item" class="draft-card">
        <div class="draft-card-body">
          <n-skeleton text style="width: 48%; height: 21px; margin-bottom: 8px" />
          <n-skeleton text style="margin-bottom: 8px" />
          <n-skeleton text style="width: 72%" />
        </div>
      </div>
    </div>

    <!-- 空态 -->
    <div v-else-if="!articles.length" class="drafts-empty">
      <div class="empty-mark">H</div>
      <h2>暂无草稿</h2>
      <p>点击「写文章」开始记录你的技术想法</p>
      <router-link class="drafts-go-write" to="/editor">去写文章</router-link>
    </div>

    <!-- 草稿列表 -->
    <div v-else class="drafts-list">
      <article v-for="article in articles" :key="article.id" class="draft-card">
        <img
          v-if="article.coverUrl"
          :src="article.coverUrl"
          class="draft-cover"
          alt="封面"
        />
        <div class="draft-card-body">
          <div class="draft-meta">
            <span class="draft-badge">草稿</span>
            <span>{{ formatArticleDateTime(article.updatedAt) }}</span>
          </div>
          <h2>{{ article.title }}</h2>
          <p>{{ article.summary || '暂无摘要' }}</p>
        </div>

        <div class="draft-card-actions">
          <button class="draft-action edit" type="button" @click="handleEdit(article)">
            <n-icon size="16"><CreateOutline /></n-icon>
            <span>编辑</span>
          </button>
          <button class="draft-action publish" type="button" @click="handlePublish(article)">
            <span>发布</span>
          </button>
          <button class="draft-action delete" type="button" @click="handleDelete(article)">
            <n-icon size="16"><TrashOutline /></n-icon>
            <span>删除</span>
          </button>
        </div>
      </article>
    </div>

    <!-- 分页 -->
    <div v-if="total > pageSize" class="drafts-pagination">
      <n-pagination
        :page="currentPage"
        :page-size="pageSize"
        :item-count="total"
        @update:page="onPageChange"
      />
    </div>
  </div>
  </MainLayout>
</template>

<style scoped>
.drafts-page {
  max-width: 800px;
  min-height: 100vh;
  margin: 0 auto;
  padding: 24px 20px 56px;
}

/* ---- 顶部 ---- */
.drafts-header {
  display: flex;
  align-items: center;
  gap: 14px;
  margin-bottom: 28px;
}

.drafts-back {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 38px;
  height: 38px;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  background: #fff;
  color: #555;
  cursor: pointer;
}
.drafts-back:hover {
  background: #f3f4f6;
}

.drafts-header h1 {
  margin: 0;
  font-size: 28px;
  color: #111;
}

.drafts-count {
  color: #999;
  font-size: 14px;
}

/* ---- 列表 ---- */
.drafts-list {
  display: grid;
  gap: 0;
}

.draft-card {
  display: flex;
  align-items: stretch;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  background: #fff;
  transition: box-shadow 0.15s;
}
.draft-card + .draft-card {
  margin-top: 14px;
}

.draft-cover {
  width: 140px;
  min-height: 100px;
  object-fit: cover;
  border-radius: 6px 0 0 6px;
  flex-shrink: 0;
}

.draft-card-body {
  flex: 1;
  min-width: 0;
  padding: 20px;
}

.draft-meta {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 8px;
  color: #999;
  font-size: 13px;
}

.draft-badge {
  display: inline-block;
  padding: 2px 10px;
  border-radius: 4px;
  background: #f3f4f6;
  color: #555;
  font-size: 12px;
  font-weight: 700;
}

.draft-card-body h2 {
  margin: 0 0 6px;
  font-size: 19px;
  color: #111;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.draft-card-body p {
  margin: 0;
  color: #999;
  font-size: 14px;
  line-height: 1.6;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

/* ---- 操作按钮 ---- */
.draft-card-actions {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 14px 14px 14px 0;
  flex-shrink: 0;
}

.draft-action {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 7px 14px;
  border-radius: 6px;
  border: 1px solid #e5e7eb;
  background: #fff;
  color: #555;
  font-size: 13px;
  cursor: pointer;
  transition:
    background 0.15s,
    border-color 0.15s,
    color 0.15s;
  white-space: nowrap;
}
.draft-action:hover {
  border-color: #ccc;
}

.draft-action.edit:hover {
  color: #2f6f73;
  border-color: #2f6f73;
  background: #f0f9f9;
}
.draft-action.publish {
  color: #fff;
  background: #2f6f73;
  border-color: #2f6f73;
}
.draft-action.publish:hover {
  background: #25595d;
  border-color: #25595d;
}
.draft-action.delete:hover {
  color: #e74c3c;
  border-color: #e74c3c;
  background: #fef2f2;
}

/* ---- 空态 ---- */
.drafts-empty {
  display: grid;
  justify-items: center;
  padding: 64px 20px;
  text-align: center;
}

.drafts-empty h2 {
  margin: 0 0 8px;
  color: #111;
}

.drafts-empty p {
  margin: 0 0 20px;
  color: #999;
}

.drafts-go-write {
  padding: 10px 24px;
  border-radius: 8px;
  color: #fff;
  background: #2f6f73;
  font-weight: 700;
  text-decoration: none;
}
.drafts-go-write:hover {
  background: #25595d;
}

/* ---- 分页 ---- */
.drafts-pagination {
  display: flex;
  justify-content: center;
  padding-top: 28px;
}
</style>
