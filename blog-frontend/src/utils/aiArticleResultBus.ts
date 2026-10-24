import type { ArticleInteractionResult } from '@/api/ai'

export interface AiArticleResultPayload {
  articleId: string
  results: ArticleInteractionResult[]
}

type Handler = (payload: AiArticleResultPayload) => void | Promise<void>

const handlers = new Set<Handler>()

export function registerAiArticleResultHandler(fn: Handler) {
  handlers.add(fn)
}

export function unregisterAiArticleResultHandler(fn: Handler) {
  handlers.delete(fn)
}

export function emitAiArticleResults(payload: AiArticleResultPayload) {
  handlers.forEach((fn) => fn(payload))
}
