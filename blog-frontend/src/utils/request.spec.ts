import type { AxiosAdapter, AxiosResponse, InternalAxiosRequestConfig } from 'axios'
import { describe, expect, it } from 'vitest'
import { createRequest } from './request'

function okResponse(config: InternalAxiosRequestConfig, data: unknown): AxiosResponse {
  return {
    data,
    status: 200,
    statusText: 'OK',
    headers: {},
    config,
  }
}

describe('createRequest', () => {
  it('does not append stale token when stored user profile is missing', async () => {
    localStorage.setItem('blog_token', 'stale-token')
    let capturedConfig: InternalAxiosRequestConfig | undefined
    const request = createRequest({
      adapter: async (config) => {
        capturedConfig = config
        return okResponse(config, {
          code: 0,
          message: 'success',
          data: [],
        })
      },
    })

    await request.get('/articles')

    expect(capturedConfig?.headers.Authorization).toBeUndefined()
    expect(localStorage.getItem('blog_token')).toBeNull()
  })

  it('uses /api as baseURL and appends bearer token when token exists', async () => {
    let capturedConfig: InternalAxiosRequestConfig | undefined
    const adapter: AxiosAdapter = async (config) => {
      capturedConfig = config
      return okResponse(config, {
        code: 0,
        message: 'success',
        data: { ok: true },
      })
    }
    const request = createRequest({
      getToken: () => 'abc-token',
      adapter,
    })

    const result = await request.get('/articles')

    expect(request.defaults.baseURL).toBe('/api')
    expect(capturedConfig?.headers.Authorization).toBe('Bearer abc-token')
    expect(result).toEqual({
      code: 0,
      message: 'success',
      data: { ok: true },
    })
  })

  it('rejects business errors returned by Result<T>', async () => {
    const request = createRequest({
      getToken: () => '',
      adapter: async (config) =>
        okResponse(config, {
          code: 40100,
          message: '未登录',
          data: null,
        }),
    })

    await expect(request.get('/users/me')).rejects.toThrow('未登录')
  })
})
