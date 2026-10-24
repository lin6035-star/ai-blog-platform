export interface UsersVO {
  id: string
  username: string
  nickname: string
  avatarUrl: string
  bio: string
  followersCount: number
  followingCount: number
  createdAt: string
}

export interface UserProfileDTO {
  nickname: string
  bio: string
}

export interface AuthVO {
  token: string
  usersVO: UsersVO
}

export interface LoginDTO {
  username: string
  password: string
  /** 滑块验证码的一次性凭证：由 SliderCaptcha 通过后拿到，服务端消费即销毁 */
  captchaVerification: string
  /**
   * 记住我。
   *
   * ⚠️ 传的是**意愿**不是时长 —— 具体多少天由后端配置决定（`jwt.expire.long` / `.short`）。
   * 勾选：后端签 30 天、前端存 localStorage；不勾：24 小时 + sessionStorage（关标签页即失效）。
   */
  rememberMe: boolean
}

export interface RegisterDTO {
  username: string
  password: string
  confirmPassword: string
  nickname: string
  /** 邮箱：找回密码的唯一通道，后端必填 */
  email: string
  /** 同 LoginDTO：滑块验证码的一次性凭证 */
  captchaVerification: string
}

/** 申请重置密码 */
export interface ForgotPasswordDTO {
  email: string
  captchaVerification: string
}

/** 用邮件链接里的 token 设置新密码 */
export interface ResetPasswordDTO {
  token: string
  newPassword: string
}
