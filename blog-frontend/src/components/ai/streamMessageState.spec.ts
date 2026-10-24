import { describe, expect, it } from 'vitest'
import type { AiMessage } from '@/api/ai'
import { completeLiveProcessSteps, finalizeFailedStreamMessage } from './streamMessageState'

function aiMessage(content: string): AiMessage {
  return {
    id: '',
    sessionId: '',
    role: 'ai',
    content,
    createdAt: '2026-10-04T00:00:00.000Z',
  }
}

describe('finalizeFailedStreamMessage', () => {
  it('removes an empty placeholder', () => {
    expect(finalizeFailedStreamMessage(aiMessage(''))).toBeUndefined()
  })

  it('keeps partial output and marks it as incomplete', () => {
    const result = finalizeFailedStreamMessage(aiMessage('已经生成的部分'))

    expect(result?.content).toBe('已经生成的部分\n\n*（生成中断，内容可能不完整）*')
  })

  it('does not append the interruption marker twice', () => {
    const interrupted = aiMessage('部分内容\n\n*（生成中断，内容可能不完整）*')

    expect(finalizeFailedStreamMessage(interrupted)).toEqual(interrupted)
  })
})

describe('completeLiveProcessSteps', () => {
  it('moves live status rows into the message process history', () => {
    const result = completeLiveProcessSteps(aiMessage('正文'), ['正在理解需求', '正在检索资料'])

    expect(result.processSteps).toEqual(['正在理解需求', '正在检索资料'])
  })
})
