import { beforeEach, describe, expect, it, vi } from 'vitest'

const getMock = vi.fn()

vi.mock('@/utils/request', () => ({
  default: {
    get: getMock,
  },
}))

describe('myArticleApi', () => {
  beforeEach(() => {
    getMock.mockReset()
  })

  it('requests profile article relation lists with pagination params', async () => {
    const { myArticleApi } = await import('./myArticle')

    myArticleApi.getLiked(2, 10)
    myArticleApi.getFavorited(3, 10)
    myArticleApi.getCommented(4, 10)

    expect(getMock).toHaveBeenNthCalledWith(1, '/users/me/liked', {
      params: { page: 2, pageSize: 10 },
    })
    expect(getMock).toHaveBeenNthCalledWith(2, '/users/me/favorited', {
      params: { page: 3, pageSize: 10 },
    })
    expect(getMock).toHaveBeenNthCalledWith(3, '/users/me/commented', {
      params: { page: 4, pageSize: 10 },
    })
  })
})
