import { describe, expect, it, vi } from 'vitest'
import type { ArticleInteractionResult } from '@/api/ai'
import {
  emitAiArticleResults,
  registerAiArticleResultHandler,
  unregisterAiArticleResultHandler,
} from '@/utils/aiArticleResultBus'

describe('ai article result bus', () => {
  it('delivers structured backend results with the target article id', () => {
    const handler = vi.fn()
    const results: ArticleInteractionResult[] = [
      {
        actionType: 'LIKE_ARTICLE',
        outcome: 'SUCCESS',
        resultCode: 'APPLIED',
        summary: '点赞成功',
        before: false,
        after: true,
      },
    ]
    registerAiArticleResultHandler(handler)

    emitAiArticleResults({ articleId: '42', results })

    expect(handler).toHaveBeenCalledOnce()
    expect(handler).toHaveBeenCalledWith({ articleId: '42', results })
    unregisterAiArticleResultHandler(handler)
  })

  it('stops delivering after unregister', () => {
    const handler = vi.fn()
    registerAiArticleResultHandler(handler)
    unregisterAiArticleResultHandler(handler)

    emitAiArticleResults({ articleId: '42', results: [] })

    expect(handler).not.toHaveBeenCalled()
  })
})
