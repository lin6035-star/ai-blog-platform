import { beforeEach, describe, expect, it, vi } from 'vitest'

const getMock = vi.fn()

vi.mock('@/utils/request', () => ({
  default: {
    get: getMock,
  },
}))

describe('articleApi', () => {
  beforeEach(() => {
    getMock.mockReset()
  })

  it('requests public articles without repeating the /api prefix', async () => {
    const { articleApi } = await import('./article')

    articleApi.getList()

    expect(getMock).toHaveBeenCalledWith('/articles', { params: undefined })
  })

  it('passes pagination query params to public articles endpoint', async () => {
    const { articleApi } = await import('./article')

    articleApi.getList({ page: 2, pageSize: 10 })

    expect(getMock).toHaveBeenCalledWith('/articles', {
      params: { page: 2, pageSize: 10 },
    })
  })

  it('passes category query params to public articles endpoint', async () => {
    const { articleApi } = await import('./article')

    articleApi.getList({ page: 1, pageSize: 10, categoryId: 2 })

    expect(getMock).toHaveBeenCalledWith('/articles', {
      params: { page: 1, pageSize: 10, categoryId: 2 },
    })
  })

  it('passes sort query params to public articles endpoint', async () => {
    const { articleApi } = await import('./article')

    articleApi.getList({ page: 1, pageSize: 10, categoryId: 2, sort: 'recommend' })

    expect(getMock).toHaveBeenCalledWith('/articles', {
      params: { page: 1, pageSize: 10, categoryId: 2, sort: 'recommend' },
    })
  })

  it('requests public article detail without repeating the /api prefix', async () => {
    const { articleApi } = await import('./article')

    articleApi.getDetail(12)

    expect(getMock).toHaveBeenCalledWith('/articles/12')
  })

  it('requests hot ranked articles without repeating the /api prefix', async () => {
    const { articleApi } = await import('./article')

    articleApi.getHotList({ page: 1, pageSize: 5 })

    expect(getMock).toHaveBeenCalledWith('/articles/hot', {
      params: { page: 1, pageSize: 5 },
    })
  })
})
