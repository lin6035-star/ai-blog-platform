import { beforeEach, describe, expect, it, vi } from 'vitest'

const getMock = vi.fn()
const postMock = vi.fn()
const deleteMock = vi.fn()

vi.mock('@/utils/request', () => ({
  default: {
    get: getMock,
    post: postMock,
    delete: deleteMock,
  },
}))

describe('publicUserApi', () => {
  beforeEach(() => {
    getMock.mockReset()
    postMock.mockReset()
    deleteMock.mockReset()
  })

  it('requests public user profile without repeating the /api prefix', async () => {
    const { publicUserApi } = await import('./publicUser')

    publicUserApi.getInfo(20)

    expect(getMock).toHaveBeenCalledWith('/users/20')
  })

  it('requests public user article tabs with pagination params', async () => {
    const { publicUserApi } = await import('./publicUser')

    publicUserApi.getArticles(20, 1, 10)
    publicUserApi.getLiked(20, 2, 10)
    publicUserApi.getFavorited(20, 3, 10)
    publicUserApi.getCommented(20, 4, 10)

    expect(getMock).toHaveBeenNthCalledWith(1, '/users/20/articles', {
      params: { page: 1, pageSize: 10 },
    })
    expect(getMock).toHaveBeenNthCalledWith(2, '/users/20/liked', {
      params: { page: 2, pageSize: 10 },
    })
    expect(getMock).toHaveBeenNthCalledWith(3, '/users/20/favorited', {
      params: { page: 3, pageSize: 10 },
    })
    expect(getMock).toHaveBeenNthCalledWith(4, '/users/20/commented', {
      params: { page: 4, pageSize: 10 },
    })
  })

  it('requests public user relation lists with pagination params', async () => {
    const { publicUserApi } = await import('./publicUser')

    publicUserApi.getFollowing(20, 1, 10)
    publicUserApi.getFollowers(20, 2, 10)

    expect(getMock).toHaveBeenNthCalledWith(1, '/users/20/following', {
      params: { page: 1, pageSize: 10 },
    })
    expect(getMock).toHaveBeenNthCalledWith(2, '/users/20/followers', {
      params: { page: 2, pageSize: 10 },
    })
  })

  it('requests follow and cancel follow endpoints', async () => {
    const { publicUserApi } = await import('./publicUser')

    publicUserApi.follow(20)
    publicUserApi.cancelFollow(20)

    expect(postMock).toHaveBeenCalledWith('/users/20/follow')
    expect(deleteMock).toHaveBeenCalledWith('/users/20/follow')
  })
})
