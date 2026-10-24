import { beforeEach, describe, expect, it, vi } from 'vitest'

const requestMock = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
  delete: vi.fn(),
}))

vi.mock('@/utils/request', () => ({
  default: requestMock,
}))

import { commentApi } from './comment'

describe('commentApi', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('loads article comments and paged replies', () => {
    commentApi.getList(10, { page: 2, pageSize: 20, sort: 'time' })
    commentApi.getReplies(30, { page: 3, pageSize: 10 })

    expect(requestMock.get).toHaveBeenNthCalledWith(1, '/articles/10/comments', {
      params: { page: 2, pageSize: 20, sort: 'time' },
    })
    expect(requestMock.get).toHaveBeenNthCalledWith(2, '/comments/30/replies', {
      params: { page: 3, pageSize: 10 },
    })
  })

  it('publishes comments and replies with content and parentId', () => {
    commentApi.create(10, { content: 'hello', parentId: null })
    commentApi.create(10, { content: 'reply', parentId: '30' })

    expect(requestMock.post).toHaveBeenNthCalledWith(1, '/articles/10/comments', {
      content: 'hello',
      parentId: null,
    })
    expect(requestMock.post).toHaveBeenNthCalledWith(2, '/articles/10/comments', {
      content: 'reply',
      parentId: '30',
    })
  })

  it('deletes, likes and unlikes comments', () => {
    commentApi.delete(30)
    commentApi.like(30)
    commentApi.unlike(30)

    expect(requestMock.delete).toHaveBeenNthCalledWith(1, '/comments/30')
    expect(requestMock.post).toHaveBeenCalledWith('/comments/30/like')
    expect(requestMock.delete).toHaveBeenNthCalledWith(2, '/comments/30/like')
  })
})
