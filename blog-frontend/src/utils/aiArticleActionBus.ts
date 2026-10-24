import type { ArticleAction } from '@/api/ai'

type Handler = (action: ArticleAction) => void

const handlers = new Set<Handler>()

export function registerAiArticleActionHandler(fn: Handler) {
  handlers.add(fn)
}

export function unregisterAiArticleActionHandler(fn: Handler) {
  handlers.delete(fn)
}

export function emitAiArticleAction(action: ArticleAction) {
  handlers.forEach((fn) => fn(action))
}
