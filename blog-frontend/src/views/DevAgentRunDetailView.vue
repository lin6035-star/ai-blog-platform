<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowBack, RefreshOutline } from '@vicons/ionicons5'
import MainLayout from '@/layouts/MainLayout.vue'
import { aiApi, type AgentRunDetail, type AgentStepRawItem } from '@/api/ai'

/**
 * V4 第一刀：开发者只读面板——run 详情。
 *
 * 与用户侧思考面板的区别：这里给的是**原始列**（step 的 inputJson / outputJson、
 * run 的 contextJson），不做脱敏也不做派生文案。contextJson 是未清洗的模型可见材料，
 * 展示时明确标注来源，避免被当成可信事实。
 */

const route = useRoute()
const router = useRouter()

const runId = computed(() => String(route.params.id ?? ''))

const loading = ref(false)
const denied = ref<string | null>(null)
const run = ref<AgentRunDetail | null>(null)
const contextJson = ref<string | null>(null)
const steps = ref<AgentStepRawItem[]>([])
/** P0-b：run 级 Token——在开发者详情的外层字段里，不在 run 摘要内（用户侧同源摘要不承载内部成本） */
const runTokens = ref({ input: 0, output: 0, total: 0, local: 0, subtree: 0 })

async function loadDetail() {
  loading.value = true
  denied.value = null
  try {
    const [detailRes, stepsRes] = await Promise.all([
      aiApi.getDevAgentRunDetail(runId.value),
      aiApi.getDevAgentRunSteps(runId.value),
    ])
    run.value = detailRes.data.run
    contextJson.value = detailRes.data.contextJson ?? null
    steps.value = stepsRes.data ?? []
    runTokens.value = {
      input: detailRes.data.inputTokens ?? 0,
      output: detailRes.data.outputTokens ?? 0,
      total: detailRes.data.totalTokens ?? 0,
      local: detailRes.data.localTokens ?? 0,
      subtree: detailRes.data.subtreeTokens ?? 0,
    }
  } catch (e) {
    denied.value = e instanceof Error ? e.message : '加载失败'
    run.value = null
    contextJson.value = null
    steps.value = []
    runTokens.value = { input: 0, output: 0, total: 0, local: 0, subtree: 0 }
  } finally {
    loading.value = false
  }
}

/**
 * 观察重复标记（V3.13 观测需要的「空转」信号）。
 *
 * 纯前端计算：outputJson 逐字相同的步骤互为重复。后端没有这个字段，
 * 也不打算为它改 Runtime——展示层能算出来的就不进埋点。
 */
const duplicateStepNos = computed(() => {
  const firstSeenAt = new Map<string, number>()
  const duplicated = new Set<number>()

  for (const step of steps.value) {
    const key = step.outputJson?.trim()
    if (!key) {
      continue
    }
    const first = firstSeenAt.get(key)
    if (first === undefined) {
      firstSeenAt.set(key, step.stepNo)
      continue
    }
    duplicated.add(first)
    duplicated.add(step.stepNo)
  }

  return duplicated
})

/**
 * P0-b：单步决策 Token（输入 / 输出）。
 *
 * 输入量随步骤递增是**正常现象**——每步都会重发累计 observation；
 * 它同时是 Token 工程后续几刀的基线读数（"哪一步最贵、重放占多少"）。
 */
function stepTokens(step: AgentStepRawItem): string {
  return `${step.inputTokens ?? 0} / ${step.outputTokens ?? 0}`
}

/** 能解析就美化，不能就原样返回（原始列不保证是合法 JSON） */
function pretty(raw?: string | null) {
  if (!raw) {
    return '(空)'
  }
  try {
    return JSON.stringify(JSON.parse(raw), null, 2)
  } catch {
    return raw
  }
}

onMounted(loadDetail)
</script>

<template>
  <MainLayout>
    <div class="dev-page">
      <header class="dev-header">
        <button class="dev-icon-btn" type="button" @click="router.back()">
          <n-icon size="20"><ArrowBack /></n-icon>
        </button>
        <h1>Agent 运行详情</h1>
        <span class="dev-hint">开发者工具 · 只读</span>
        <button class="dev-icon-btn dev-refresh" type="button" @click="loadDetail">
          <n-icon size="18"><RefreshOutline /></n-icon>
        </button>
      </header>

      <n-alert v-if="denied" type="warning" title="无权访问" class="dev-denied">
        {{ denied }}
      </n-alert>

      <n-spin v-else :show="loading">
        <template v-if="run">
          <!-- 基本信息 -->
          <section class="dev-card">
            <n-descriptions :column="2" label-placement="left" size="small">
              <n-descriptions-item label="Run ID">{{ run.id }}</n-descriptions-item>
              <n-descriptions-item label="状态">{{ run.status }}</n-descriptions-item>
              <n-descriptions-item label="目标" :span="2">{{ run.goal }}</n-descriptions-item>
              <n-descriptions-item label="Token（入 / 出 / 合计）" :span="2">
                {{ runTokens.input }} / {{ runTokens.output }} / {{ runTokens.total }}
              </n-descriptions-item>
              <n-descriptions-item label="Token（本地 / 子树）" :span="2">
                {{ runTokens.local }} / {{ runTokens.subtree }}
              </n-descriptions-item>
              <n-descriptions-item label="步数">
                {{ run.usedSteps ?? 0 }} / {{ run.maxSteps ?? 0 }}
              </n-descriptions-item>
              <n-descriptions-item label="会话">{{ run.sessionId ?? '-' }}</n-descriptions-item>
              <n-descriptions-item label="创建">{{ run.createdAt }}</n-descriptions-item>
              <n-descriptions-item label="更新">{{ run.updatedAt }}</n-descriptions-item>
              <n-descriptions-item v-if="run.errorMessage" label="错误" :span="2">
                <span class="dev-error-text">{{ run.errorMessage }}</span>
              </n-descriptions-item>
            </n-descriptions>

            <div v-if="run.finalAnswer" class="dev-block">
              <div class="dev-section-title">最终回答</div>
              <p class="dev-final-text">{{ run.finalAnswer }}</p>
            </div>
          </section>

          <!-- 计划（V3.13，仅文章域可能非空） -->
          <section v-if="run.plan?.length" class="dev-card">
            <div class="dev-section-title">计划（plan）</div>
            <ol class="dev-plan">
              <li v-for="(item, i) in run.plan" :key="i">{{ item }}</li>
            </ol>
          </section>

          <!-- 步骤 -->
          <section class="dev-card">
            <div class="dev-section-title">步骤（{{ steps.length }}）</div>
            <div v-if="!steps.length" class="dev-empty">无步骤记录</div>

            <div
              v-for="step in steps"
              :key="step.stepNo"
              class="dev-step"
              :class="`is-${(step.status || '').toLowerCase()}`"
            >
              <div class="dev-step-head">
                <span class="dev-step-no">{{ step.stepNo }}</span>
                <span class="dev-step-action">{{ step.actionType }}</span>
                <span class="dev-step-status">{{ step.status }}</span>
                <span v-if="duplicateStepNos.has(step.stepNo)" class="dev-step-dup">观察重复</span>
                <span class="dev-step-tokens" title="本步决策 Token（输入 / 输出）">
                  tok {{ stepTokens(step) }}
                </span>
                <span class="dev-step-duration">{{ step.durationMs ?? 0 }}ms</span>
              </div>

              <p v-if="step.thoughtSummary" class="dev-step-thought">{{ step.thoughtSummary }}</p>
              <p v-if="step.errorMessage" class="dev-step-error">{{ step.errorMessage }}</p>

              <n-collapse>
                <n-collapse-item :name="step.stepNo" title="input / output">
                  <div class="dev-raw-label">input</div>
                  <pre class="dev-pre">{{ pretty(step.inputJson) }}</pre>
                  <div class="dev-raw-label">output</div>
                  <pre class="dev-pre">{{ pretty(step.outputJson) }}</pre>
                </n-collapse-item>
              </n-collapse>
            </div>
          </section>

          <!-- 原始上下文 -->
          <section class="dev-card">
            <div class="dev-section-title">原始 contextJson（observations）</div>
            <p class="dev-warn">
              未清洗的模型可见材料（含文章正文片段），可能有长度截断——排查用，不要当作可信事实。
            </p>
            <n-collapse>
              <n-collapse-item name="ctx" title="展开查看">
                <pre class="dev-pre">{{ pretty(contextJson) }}</pre>
              </n-collapse-item>
            </n-collapse>
          </section>
        </template>
      </n-spin>
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

/* ---- 顶部 ---- */
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

/* ---- 卡片 ---- */
.dev-card {
  margin-bottom: 18px;
  padding: 16px 18px;
  border: 1px solid rgba(128, 128, 128, 0.22);
  border-radius: 10px;
}

.dev-section-title {
  margin-bottom: 10px;
  font-size: 14px;
  font-weight: 600;
}

.dev-block {
  margin-top: 16px;
}

.dev-final-text {
  margin: 0;
  white-space: pre-wrap;
  line-height: 1.7;
}

.dev-error-text,
.dev-step-error {
  color: #d03050;
  white-space: pre-wrap;
}

.dev-raw-label {
  margin: 10px 0 4px;
  font-size: 12px;
  font-weight: 600;
  color: #888;
}

.dev-empty {
  color: #999;
  font-size: 13px;
}

.dev-warn {
  margin: 0 0 8px;
  font-size: 12px;
  color: #d08000;
}

/* ---- 计划 ---- */
.dev-plan {
  margin: 0;
  padding-left: 20px;
  line-height: 1.9;
}

/* ---- 步骤 ---- */
.dev-step {
  margin-bottom: 12px;
  padding: 10px 12px;
  border-left: 3px solid rgba(128, 128, 128, 0.3);
  background: rgba(128, 128, 128, 0.06);
  border-radius: 0 6px 6px 0;
}

.dev-step.is-success {
  border-left-color: #18a058;
}

.dev-step.is-failed {
  border-left-color: #d03050;
}

.dev-step.is-skipped {
  border-left-color: #8a8f98;
}

.dev-step.is-running {
  border-left-color: #2080f0;
}

.dev-step-head {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 13px;
}

.dev-step-no {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 22px;
  height: 22px;
  padding: 0 5px;
  border-radius: 11px;
  background: rgba(128, 128, 128, 0.18);
  font-weight: 600;
}

.dev-step-action {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-weight: 600;
}

.dev-step-status {
  font-size: 12px;
  color: #888;
}

.dev-step-tokens {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 12px;
  color: #888;
}

.dev-step-duration {
  margin-left: auto;
  font-size: 12px;
  color: #888;
}

.dev-step-dup {
  padding: 1px 6px;
  border-radius: 4px;
  background: rgba(208, 128, 0, 0.14);
  color: #d08000;
  font-size: 12px;
}

.dev-step-thought {
  margin: 8px 0 0;
  line-height: 1.7;
}

/* ---- 原始 JSON ---- */
.dev-pre {
  max-height: 320px;
  margin: 0;
  padding: 10px 12px;
  overflow: auto;
  border-radius: 6px;
  background: rgba(128, 128, 128, 0.1);
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 12px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-word;
}
</style>
