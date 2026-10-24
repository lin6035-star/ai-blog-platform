import { beforeEach, describe, expect, it, vi } from 'vitest'

const getMock = vi.fn()
const postMock = vi.fn()
const putMock = vi.fn()

vi.mock('@/utils/request', () => ({
  default: {
    get: getMock,
    post: postMock,
    put: putMock,
  },
}))

describe('userApi', () => {
  beforeEach(() => {
    getMock.mockReset()
    postMock.mockReset()
    putMock.mockReset()
  })

  it('requests current user profile without repeating the /api prefix', async () => {
    const { userApi } = await import('./user')

    userApi.getMe()

    expect(getMock).toHaveBeenCalledWith('/users/me')
  })

  it('updates current user profile without repeating the /api prefix', async () => {
    const { userApi } = await import('./user')
    const payload = { nickname: '新的昵称', bio: '新的个人简介' }

    userApi.updateProfile(payload)

    expect(putMock).toHaveBeenCalledWith('/users/me/profile', payload)
  })

  it('uploads avatar with multipart file field named file', async () => {
    const { userApi } = await import('./user')
    const file = new File(['avatar'], 'avatar.png', { type: 'image/png' })

    userApi.uploadAvatar(file)

    const [url, formData, config] = postMock.mock.calls[0]
    expect(url).toBe('/users/me/avatar')
    expect(formData.get('file')).toBe(file)
    expect(config.headers['Content-Type']).toBe('multipart/form-data')
  })
})
