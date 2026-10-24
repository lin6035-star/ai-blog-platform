<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useMessage } from 'naive-ui'
import { ArrowBack, CheckmarkCircleOutline, EllipseOutline } from '@vicons/ionicons5'
import MainLayout from '@/layouts/MainLayout.vue'
import { learningPlanApi, type LearningPlanDetail, type LearningStageProgress } from '@/api/learningPlan'

const route = useRoute()
const router = useRouter()
const message = useMessage()

const loading = ref(false)
const detail = ref<LearningPlanDetail | null>(null)

const progressPercent = computed(() => {
  if (!detail.value || detail.value.totalTasks === 0) return 0
  return Math.round((detail.value.doneTasks / detail.value.totalTasks) * 100)
})

async function loadDetail() {
  loading.value = true
  try {
    const res = await learningPlanApi.detail(route.params.id as string)
    detail.value = res.data
  } catch {
    message.error('学习计划加载失败')
  } finally {
    loading.value = false
  }
}

//任务勾选：乐观更新，失败回滚（进度实时聚合，前端本地改 doneTasks）
async function toggleTask(stage: LearningStageProgress, taskIndex: number) {
  if (!detail.value) return
  const task = stage.tasks[taskIndex]
  if (!task) return

  const next = !task.done
  task.done = next
  detail.value.doneTasks += next ? 1 : -1
  try {
    await learningPlanApi.updateTaskDone(detail.value.plan.id, stage.id, taskIndex, next)
  } catch {
    task.done = !next
    detail.value.doneTasks += next ? -1 : 1
    message.error('任务状态更新失败')
  }
}

//返回计划列表：优先走浏览器历史（back 不新增记录），直接 URL 进入时兜底跳转
function goBackToList() {
  if (router.options.history.state.back) {
    router.back()
  } else {
    router.replace('/me/learning-plans')
  }
}

onMounted(loadDetail)
</script>

<template>
  <MainLayout>
    <div class="learning-plan-detail-page">
      <header class="learning-plan-detail-header">
        <button class="learning-plans-back" type="button" @click="goBackToList">
          <n-icon :component="ArrowBack" size="18" />
          返回计划列表
        </button>
        <template v-if="detail">
          <h1>{{ detail.plan.title }}</h1>
          <p v-if="detail.plan.goal" class="learning-plan-detail-goal">{{ detail.plan.goal }}</p>

          <div class="learning-plan-progress">
            <div class="learning-plan-progress-bar">
              <div class="learning-plan-progress-fill" :style="{ width: progressPercent + '%' }" />
            </div>
            <span class="learning-plan-progress-text">
              {{ detail.doneTasks }} / {{ detail.totalTasks }} 项完成（{{ progressPercent }}%）
            </span>
          </div>
        </template>
      </header>

      <div v-if="loading" class="learning-plans-empty">
        <n-spin size="small" />
      </div>

      <template v-else-if="detail">
        <section
          v-for="(stage, si) in detail.stages"
          :key="stage.id"
          class="learning-plan-stage-card"
        >
          <h2 class="learning-plan-stage-title">阶段 {{ si + 1 }}：{{ stage.title }}</h2>
          <ul class="learning-plan-task-list">
            <li
              v-for="(task, ti) in stage.tasks"
              :key="ti"
              class="learning-plan-task"
              :class="{ 'learning-plan-task--done': task.done }"
            >
              <button
                class="learning-plan-task-toggle"
                type="button"
                :title="task.done ? '标记为未完成' : '标记为已完成'"
                @click="toggleTask(stage, ti)"
              >
                <n-icon
                  :component="task.done ? CheckmarkCircleOutline : EllipseOutline"
                  :class="task.done ? 'task-icon-done' : 'task-icon-todo'"
                  size="16"
                />
              </button>
              <span>{{ task.title }}</span>
            </li>
          </ul>
        </section>
      </template>

      <div v-else class="learning-plans-empty">
        <p>计划不存在或无权访问。</p>
      </div>
    </div>
  </MainLayout>
</template>

<style scoped>
.learning-plan-detail-page {
  max-width: 860px;
  margin: 0 auto;
  padding: 24px 16px 48px;
}

.learning-plan-detail-header {
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

.learning-plan-detail-header h1 {
  margin: 0 0 4px;
  font-size: 22px;
}

.learning-plan-detail-goal {
  margin: 0 0 14px;
  color: #6b7280;
  font-size: 14px;
}

.learning-plan-progress {
  display: flex;
  align-items: center;
  gap: 12px;
}

.learning-plan-progress-bar {
  flex: 1;
  max-width: 320px;
  height: 8px;
  border-radius: 999px;
  background: #e5e7eb;
  overflow: hidden;
}

.learning-plan-progress-fill {
  height: 100%;
  border-radius: 999px;
  background: #2563eb;
  transition: width 0.3s;
}

.learning-plan-progress-text {
  font-size: 13px;
  color: #4b5563;
}

.learning-plans-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  padding: 48px 0;
  color: #9ca3af;
}

.learning-plan-stage-card {
  border: 1px solid #e5e7eb;
  border-radius: 10px;
  padding: 16px;
  margin-bottom: 12px;
  background: #fff;
}

.learning-plan-stage-title {
  margin: 0 0 10px;
  font-size: 15px;
}

.learning-plan-task-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.learning-plan-task {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13.5px;
  color: #4b5563;
}

.learning-plan-task-toggle {
  display: inline-flex;
  align-items: center;
  border: none;
  background: none;
  padding: 0;
  cursor: pointer;
}

.learning-plan-task--done span {
  text-decoration: line-through;
  color: #9ca3af;
}

.task-icon-done {
  color: #059669;
}

.task-icon-todo {
  color: #d1d5db;
}
</style>
