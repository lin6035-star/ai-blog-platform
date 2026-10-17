import request from '@/utils/request'
import type { AuthVO, ForgotPasswordDTO, LoginDTO, RegisterDTO, ResetPasswordDTO } from '@/types/user'

export const authApi = {
  login(data: LoginDTO) {
    return request.post<AuthVO>('/auth/login', data)
  },
  register(data: RegisterDTO) {
    return request.post<AuthVO>('/auth/register', data)
  },
  logout() {
    // 单独设短超时：退出登录不该被后端拖住（默认 60s 太长），
    // 拿不到响应时前端仍会清本地状态，见 ProfileView 的 handleLogout
    return request.post<null>('/auth/logout', undefined, { timeout: 3000 })
  },
  getGitHubAuthUrl() {
    return request.get<{ url: string }>('/auth/github/url')
  },
  /**
   * 申请重置密码。
   *
   * ⚠️ 后端**刻意**对「邮箱存不存在」返回完全一样的响应（防枚举），
   * 所以这里的返回值里没有任何"是否成功找到账号"的信息，前端提示也必须是固定文案。
   */
  forgotPassword(data: ForgotPasswordDTO) {
    return request.post<null>('/auth/password/forgot', data)
  },
  /** 用邮件链接里的 token 设置新密码；成功后该账号所有已登录设备都会失效 */
  resetPassword(data: ResetPasswordDTO) {
    return request.post<null>('/auth/password/reset', data)
  },
}
