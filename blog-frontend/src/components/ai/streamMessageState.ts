import type { AiMessage } from '@/api/ai'

const STREAM_INTERRUPTED_MARKER = '*（生成中断，内容可能不完整）*'

export function finalizeFailedStreamMessage(message: AiMessage | undefined): AiMessage | undefined {
  if (!message || message.role !== 'ai' || !message.content.trim()) return undefined
  if (message.content.includes(STREAM_INTERRUPTED_MARKER)) return message

  return {
    ...message,
    content: `${message.content}\n\n${STREAM_INTERRUPTED_MARKER}`,
  }
}

export function completeLiveProcessSteps(message: AiMessage, liveSteps: string[]): AiMessage {
  if (!liveSteps.length) return message
  return {
    ...message,
    processSteps: [...(message.processSteps ?? []), ...liveSteps],
  }
}
