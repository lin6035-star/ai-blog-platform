<script setup lang="ts">
import { h, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { NTag } from 'naive-ui'
import { ArrowBack, RefreshOutline } from '@vicons/ionicons5'
import MainLayout from '@/layouts/MainLayout.vue'
import { aiApi, type AgentRunSummaryItem } from '@/api/ai'

/**
 * V4 第一刀：开发者只读面板——run 列表。
 *
 * 边界：只读；数据范围限「自己的 run」；后端白名单门禁（403 即无权）。
 */

type TagType = 'default' | 'error' | 'info' | 'success' | 'warning'

const router = useRouter()

const loading = ref(false)
const runs = ref<AgentRunSummaryItem[]>([])
const currentPage = ref(1)
const pageSize = 20
const total = ref(0)
/** 门禁失败原因（面板关闭 / 不在白名单），非空时不渲染表格 */
const denied = ref<string | null>(null)

function statusTagType(status?: string | null): TagType {
  switch (status) {
    case 'COMPLETED':
      return 'success'
    case 'RUNNING':
      return 'info'
    case 'FAILED':
      return 'error'
    case 'CANCELLED':
      return 'default'
    default:
      return 'warning' // WAITING_USER / WAITING_*_CONFIRM
  }
}

const columns = [
  { title: 'Run ID', key: 'id', width: 210, ellipsis: { tooltip: true } },
  {
    title: '状态',
    key: 'status',
    width: 170,
    render: (row: AgentRunSummaryItem) =>
      h(NTag, { size: 'small', type: statusTagType(row.status) }, {
        default: () => row.status ?? '-',
      }),
  },
  { title: '目标', key: 'goal', ellipsis: { tooltip: true } },
  {
    title: '步数',
    key: 'usedSteps',
    width: 90,
    render: (row: AgentRunSummaryItem) => `${row.usedSteps ?? 0} / ${row.maxSteps ?? 0}`,
  },
  { title: '创建时间', key: 'createdAt', width: 190 },
]

const rowProps = (row: AgentRunSummaryItem) => ({
  style: 'cursor: pointer',
  onClick: () => router.push(`/dev/agent-runs/${row.id}`),
})

async function loadRuns() {
  loading.value = true
  denied.value = null
  try {
    const res = await aiApi.listDevAgentRuns({ page: currentPage.value, pageSize })
    runs.value = res.data.list ?? []
    total.value = res.data.total ?? 0
  } catch (e) {
    denied.value = e instanceof Error ? e.message : '加载失败'
    runs.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

function onPageChange(page: number) {
  currentPage.value = page
  loadRuns()
}

onMounted(loadRuns)
</script>

<template>
  <MainLayout>
    <div class="dev-page">
      <header class="dev-header">
        <button class="dev-icon-btn" type="button" @click="router.back()">
          <n-icon size="20"><ArrowBack /></n-icon>
        </button>
        <h1>Agent 运行记录</h1>
        <span class="dev-hint">开发者工具 · 只读</span>
        <button class="dev-icon-btn dev-refresh" type="button" @click="loadRuns">
          <n-icon size="18"><RefreshOutline /></n-icon>
        </button>
      </header>

      <n-alert v-if="denied" type="warning" title="无权访问" class="dev-denied">
        {{ denied }}
      </n-alert>

      <template v-else>
        <n-data-table
          :columns="columns"
          :data="runs"
          :loading="loading"
          :bordered="false"
          :row-props="rowProps"
          size="small"
        />

        <div v-if="total > pageSize" class="dev-pagination">
          <n-pagination
            :page="currentPage"
            :page-size="pageSize"
            :item-count="total"
            @update:page="onPageChange"
          />
        </div>
      </template>
    </div>
  </MainLayout>
</template>

<style scoped>
.dev-page {
  max-width: 1100px;
  min-height: 100vh;
  margin: 0 auto;
  padding: 24px 20px 56px;
}

.dev-header {
  display: flex;
  align-items: center;
  gap: 14px;
  margin-bottom: 24px;
}

.dev-header h1 {
  margin: 0;
  font-size: 20px;
  font-weight: 600;
}

.dev-icon-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 34px;
  height: 34px;
  border: none;
  border-radius: 8px;
  background: transparent;
  color: inherit;
  cursor: pointer;
}

.dev-icon-btn:hover {
  background: rgba(128, 128, 128, 0.12);
}

.dev-refresh {
  margin-left: auto;
}

.dev-hint {
  padding: 1px 6px;
  border: 1px solid rgba(128, 128, 128, 0.35);
  border-radius: 4px;
  font-size: 12px;
  color: #999;
}

.dev-denied {
  margin-top: 8px;
}

.dev-pagination {
  display: flex;
  justify-content: center;
  margin-top: 20px;
}
</style>
