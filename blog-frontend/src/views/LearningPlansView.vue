<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useDialog, useMessage } from 'naive-ui'
import { ArrowBack, BulbOutline, TrashOutline } from '@vicons/ionicons5'
import MainLayout from '@/layouts/MainLayout.vue'
import { learningPlanApi, type LearningPlan } from '@/api/learningPlan'


const router = useRouter()
const message = useMessage()

const loading = ref(false)
const plans = ref<LearningPlan[]>([])

const statusLabel: Record<string, string> = {
  ACTIVE: '进行中',
  COMPLETED: '已完成',
  ARCHIVED: '已归档',
}

const dialog = useDialog()

function confirmDelete(plan: LearningPlan) {
  dialog.warning({
    title: '确认删除学习计划',
    content: `确定永久删除《${plan.title}》吗？计划中的全部阶段和任务都会被删除，无法恢复。`,
    positiveText: '删除',
    negativeText: '取消',
    onPositiveClick: async () => {
      try {
        await learningPlanApi.delete(plan.id)
        plans.value = plans.value.filter((item) => item.id !== plan.id)
        message.success('学习计划已删除')
      } catch {
        message.error('学习计划删除失败')
      }
    },
  })
}

async function loadPlans() {
  loading.value = true
  try {
    const res = await learningPlanApi.listMine()
    plans.value = res.data ?? []
  } catch {
    message.error('学习计划加载失败')
  } finally {
    loading.value = false
  }
}

function goDetail(id: string) {
  router.push(`/me/learning-plans/${id}`)
}

//返回个人中心：优先走浏览器历史（back 不新增记录），直接 URL 进入时兜底跳转
function goBackToProfile() {
  if (router.options.history.state.back) {
    router.back()
  } else {
    router.replace('/me')
  }
}

onMounted(loadPlans)
</script>

<template>
  <MainLayout>
    <div class="learning-plans-page">
      <header class="learning-plans-header">
        <button class="learning-plans-back" type="button" @click="goBackToProfile">
          <n-icon :component="ArrowBack" size="18" />
          返回个人中心
        </button>
        <h1>我的学习计划</h1>
        <p class="learning-plans-subtitle">由 AI 助手生成、你确认保存的长期学习路线</p>
      </header>

      <div v-if="loading" class="learning-plans-empty">
        <n-spin size="small" />
      </div>

      <div v-else-if="!plans.length" class="learning-plans-empty">
        <n-icon :component="BulbOutline" size="36" />
        <p>还没有学习计划。</p>
        <p class="learning-plans-hint">在任意页面的 AI 助手里说"我想系统学习 Redis，帮我规划路线"试试。</p>
      </div>

      <ul v-else class="learning-plans-list">
        <li
          v-for="plan in plans"
          :key="plan.id"
          class="learning-plan-card"
          role="button"
          tabindex="0"
          @click="goDetail(plan.id)"
          @keydown.enter="goDetail(plan.id)"
        >
          <div class="learning-plan-card-head">
            <h2>{{ plan.title }}</h2>
            <div class="learning-plan-card-actions">
              <span class="learning-plan-status" :class="`learning-plan-status--${plan.status.toLowerCase()}`">
                {{ statusLabel[plan.status] ?? plan.status }}
              </span>
              <button
                class="learning-plan-delete"
                type="button"
                title="删除学习计划"
                @click.stop="confirmDelete(plan)"
                @keydown.enter.stop
              >
                <n-icon :component="TrashOutline" size="16" />
              </button>
            </div>
          </div>
          <p v-if="plan.goal" class="learning-plan-goal">{{ plan.goal }}</p>
          <p class="learning-plan-date">创建于 {{ new Date(plan.createdAt).toLocaleDateString('zh-CN') }}</p>
        </li>
      </ul>
    </div>
  </MainLayout>
</template>

<style scoped>
.learning-plans-page {
  max-width: 860px;
  margin: 0 auto;
  padding: 24px 16px 48px;
}

.learning-plans-header {
  margin-bottom: 20px;
}

.learning-plans-back {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  border: none;
  background: none;
  color: #6b7280;
  cursor: pointer;
  font-size: 13px;
  padding: 0;
  margin-bottom: 12px;
}

.learning-plans-back:hover {
  color: #374151;
}

.learning-plans-header h1 {
  margin: 0 0 4px;
  font-size: 22px;
}

.learning-plans-subtitle {
  margin: 0;
  color: #6b7280;
  font-size: 14px;
}

.learning-plans-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  padding: 48px 0;
  color: #9ca3af;
}

.learning-plans-hint {
  font-size: 13px;
  color: #9ca3af;
  margin: 0;
}

.learning-plans-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.learning-plan-card {
  border: 1px solid #e5e7eb;
  border-radius: 10px;
  padding: 16px;
  cursor: pointer;
  transition: box-shadow 0.15s, border-color 0.15s;
  background: #fff;
}

.learning-plan-card:hover {
  border-color: #60a5fa;
  box-shadow: 0 2px 8px rgb(0 0 0 / 6%);
}

.learning-plan-card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.learning-plan-card-head h2 {
  margin: 0;
  font-size: 16px;
}

.learning-plan-status {
  font-size: 12px;
  padding: 2px 10px;
  border-radius: 999px;
  background: #eff6ff;
  color: #2563eb;
}

.learning-plan-status--completed {
  background: #ecfdf5;
  color: #059669;
}

.learning-plan-status--archived {
  background: #f3f4f6;
  color: #6b7280;
}

.learning-plan-goal {
  margin: 8px 0 4px;
  font-size: 13px;
  color: #4b5563;
}

.learning-plan-date {
  margin: 0;
  font-size: 12px;
  color: #9ca3af;
}

.learning-plan-card-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.learning-plan-delete {
  display: inline-flex;
  align-items: center;
  border: none;
  background: none;
  color: #9ca3af;
  cursor: pointer;
  padding: 2px;
}

.learning-plan-delete:hover {
  color: #dc2626;
}
</style>
