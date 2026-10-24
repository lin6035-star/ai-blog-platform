// @vitest-environment node
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

const srcRoot = resolve(__dirname, '..')

function readSource(path: string) {
  return readFileSync(resolve(srcRoot, path), 'utf8')
}

describe('article interaction result flow', () => {
  it('routes STOP actionResults through the dedicated refresh bus', () => {
    const api = readSource('api/ai.ts')
    const assistant = readSource('components/ai/AiAssistant.vue')

    expect(api).toContain('actionResults?: ArticleInteractionResult[]')
    expect(api).toContain('data.actionResults')
    expect(assistant).toContain('emitAiArticleResults')
    expect(assistant).toContain('actionResults')
  })

  it('refreshes authority for the matching article without executing writes again', () => {
    const detail = readSource('views/ArticleDetailView.vue')
    const start = detail.indexOf('async function handleAiArticleResults')
    const end = detail.indexOf('\n}', start) + 2
    const handler = detail.slice(start, end)

    expect(start).toBeGreaterThan(-1)
    expect(handler).toContain('loadArticle()')
    expect(handler).not.toContain('handleLike')
    expect(handler).not.toContain('handleFavorite')
    expect(handler).not.toContain('handleAuthorFollow')
    expect(handler).not.toContain('articleApi.like')
    expect(handler).not.toContain('publicUserApi.follow')
    expect(detail).toContain('registerAiArticleResultHandler(handleAiArticleResults)')
    expect(detail).toContain('unregisterAiArticleResultHandler(handleAiArticleResults)')
  })

  it('resyncs the bound task message and steps after workflow terminal actions', () => {
    const assistant = readSource('components/ai/AiAssistant.vue')
    const cancelStart = assistant.indexOf('async function cancelWorkflow()')
    const cancelEnd = assistant.indexOf('\n}', cancelStart) + 2
    const cancelHandler = assistant.slice(cancelStart, cancelEnd)
    const refreshStart = assistant.indexOf('async function refreshBoundTaskResult(')
    const refreshEnd = assistant.indexOf('\n}', refreshStart) + 2
    const refreshHandler = assistant.slice(refreshStart, refreshEnd)

    expect(cancelStart).toBeGreaterThan(-1)
    expect(cancelHandler).toContain('await refreshBoundTaskResult(workflowId)')
    expect(refreshStart).toBeGreaterThan(-1)
    expect(refreshHandler).toContain('aiApi.getAgentRunSteps')
    expect(refreshHandler).toContain('aiApi.getMessages')
    expect(refreshHandler).toContain('innerSteps: previousTaskSteps.find')
    expect(assistant).toContain('await refreshBoundTaskResult(data.workflow.id)')
    expect(assistant).toMatch(
      /msg\.agentRunId === id && \(!msg\.thinkingSteps \|\| !msg\.plan \|\| !msg\.taskSteps\)/,
    )
  })

  it('keeps task rows as stable goals instead of repeating execution summaries', () => {
    const api = readSource('api/ai.ts')
    const assistant = readSource('components/ai/AiAssistant.vue')
    const labelStart = assistant.indexOf('function taskResultText(task: TaskStepEvent)')
    const labelEnd = assistant.indexOf('\n}', labelStart) + 2
    const labelHandler = assistant.slice(labelStart, labelEnd)

    expect(api).toContain('goalEvidence?: string[]')
    expect(labelStart).toBeGreaterThan(-1)
    expect(labelHandler).toContain('return goal || taskTypeLabel(task.type)')
    expect(labelHandler).not.toContain('task.summary')
    expect(assistant).toContain('taskResultText(task)')
    expect(assistant).toContain("type: s.type ?? ''")
    expect(assistant).toContain('goalEvidence: s.goalEvidence ?? []')
  })

  it('paces buffered task replies without slowing normal chat streaming', () => {
    const api = readSource('api/ai.ts')
    const assistant = readSource('components/ai/AiAssistant.vue')
    const sendStart = assistant.indexOf('async function send(')
    const sendHandler = assistant.slice(sendStart)
    const lineLoopStart = api.indexOf('for (const line of lines)')
    const lineLoopBody = api.slice(lineLoopStart, api.indexOf('const trimmed = line.trim()', lineLoopStart))

    expect(sendStart).toBeGreaterThan(-1)
    expect(assistant).toContain('const TASK_REPLY_CHUNK_DELAY_MS = 24')
    expect(sendHandler).toContain('let taskReplyPacing = false')
    expect(sendHandler).toContain('taskReplyPacing = true')
    expect(sendHandler).toContain('if (taskReplyPacing) await sleep(TASK_REPLY_CHUNK_DELAY_MS)')
    expect(lineLoopStart).toBeGreaterThan(-1)
    expect(lineLoopBody).toContain('if (signal?.aborted)')
    expect(lineLoopBody).toContain('callbacks.onAbort?.()')
  })
})
