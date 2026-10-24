import axios, {
  type AxiosAdapter,
  type AxiosInstance,
  type AxiosRequestConfig,
  type AxiosResponse,
  type InternalAxiosRequestConfig,
} from 'axios'
import type { Result } from '@/types/result'

interface CreateRequestOptions {
  getToken?: () => string
  adapter?: AxiosAdapter
  onUnauthorized?: () => void
}

let unauthorizedHandler: (() => void) | undefined

/**
 * 注册未授权处理器（token 失效的统一出口）。
 *
 * 由 router 在初始化时注入，而不是在这里直接 import store / router：
 * utils 反向依赖会形成循环依赖，且模块顶层执行时 pinia 还没装上。
 * createRequest 显式传入的 onUnauthorized 优先于它。
 */
export function setUnauthorizedHandler(handler: () => void) {
  unauthorizedHandler = handler
}

type AxiosMethods = 'get' | 'post' | 'put' | 'patch' | 'delete'
const TOKEN_KEY = 'blog_token'
const USER_KEY = 'blog_user'

export type ResultRequest = Omit<AxiosInstance, AxiosMethods> & {
  get<T = unknown>(url: string, config?: AxiosRequestConfig): Promise<Result<T>>
  post<T = unknown>(
    url: string,
    data?: unknown,
    config?: AxiosRequestConfig,
  ): Promise<Result<T>>
  put<T = unknown>(
    url: string,
    data?: unknown,
    config?: AxiosRequestConfig,
  ): Promise<Result<T>>
  patch<T = unknown>(
    url: string,
    data?: unknown,
    config?: AxiosRequestConfig,
  ): Promise<Result<T>>
  delete<T = unknown>(url: string, config?: AxiosRequestConfig): Promise<Result<T>>
}

/**
 * 读取登录凭证。
 *
 * **两个 storage 都要查**：「记住我」勾选时存 `localStorage`、不勾时存 `sessionStorage`
 * （见 auth store 的 `setAuth`）。任何一处漏掉都会让一半用户"莫名未登录"，
 * 而且症状是请求 401 ——很难联想到是存储位置的问题。
 *
 * 同一份凭据只会存在于一处（`setAuth` 写入前先清另一处），所以不存在读到旧值的情况。
 */
export function readAuthToken() {
  for (const storage of [localStorage, sessionStorage]) {
    const token = storage.getItem(TOKEN_KEY) ?? ''
    if (!token) {
      continue
    }

    const rawUser = storage.getItem(USER_KEY)
    if (!rawUser) {
      // 有 token 没用户信息：手工改过的残留状态，丢掉，免得带着脏数据发请求
      storage.removeItem(TOKEN_KEY)
      continue
    }

    try {
      JSON.parse(rawUser)
      return token
    } catch {
      storage.removeItem(TOKEN_KEY)
      storage.removeItem(USER_KEY)
    }
  }

  return ''
}

export function createRequest(options: CreateRequestOptions = {}): ResultRequest {
  const instance = axios.create({
    baseURL: '/api',
    timeout: 60000,
    adapter: options.adapter,
  })

  // 未授权统一出口：显式注入优先，否则用全局注册的（见 setUnauthorizedHandler）
  const handleUnauthorized = options.onUnauthorized ?? (() => unauthorizedHandler?.())

  instance.interceptors.request.use((config: InternalAxiosRequestConfig) => {
    const token = options.getToken ? options.getToken() : readAuthToken()

    if (token) {
      config.headers.Authorization = `Bearer ${token}`
    }

    return config
  })

  instance.interceptors.response.use(
    (response) => {
      const result = response.data as Result

      if (typeof result?.code === 'number' && result.code !== 0) {
        if (result.code === 40100) {
          handleUnauthorized()
        }

        return Promise.reject(new Error(result.message || '请求失败'))
      }

      return result as unknown as AxiosResponse
    },
    (error) => {
      // 鉴权拦截器返回的是 HTTP 401（body 里同样带 40100），不经过上面的业务码分支——
      // token 过期时这条才是主路径，漏了它前端就完全无感
      if (error?.response?.status === 401) {
        handleUnauthorized()
      }

      /*
       * 2026-10-17 上线审计补：非 2xx 响应里后端其实带了可读的 message
       * （登录被限流 429、余额不足 402、AI 繁忙 503 都是「HTTP 状态码 + Result」），
       * 但之前把 axios 的原始 error 直接抛出去，界面显示的是英文
       * "Request failed with status code 429" —— 后端的中文提示被丢弃了。
       * 这里把后端 message 提到 error.message，全仓直接展示 error.message 的调用点一起受益。
       * `response.data` 也可能是字符串（Nginx 的 HTML 错误页），所以先判类型。
       */
      const backendMessage = error?.response?.data?.message
      if (typeof backendMessage === 'string' && backendMessage.trim()) {
        const normalized = new Error(backendMessage) as Error & { cause?: unknown }
        // 保留原始错误便于调试；不参与界面展示
        normalized.cause = error
        return Promise.reject(normalized)
      }

      return Promise.reject(error)
    },
  )

  return instance as ResultRequest
}

const request = createRequest()

export default request
