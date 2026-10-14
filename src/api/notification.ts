import request from '@/utils/request'
import type { NotificationItem } from '@/types/notification'
import type { PageData } from '@/types/result'

/**
 * 站内通知。路径集中在 api 层，组件不直写 URL（api-convention）。
 *
 * 全部挂在 `/users/me/notifications` 下 —— 按约定，"当前登录用户自己的资源"
 * 用 `/api/users/me/...`，于是后端那边自动落在 JwtInterceptor 的保护范围里。
 */
export const notificationApi = {
  /** 通知列表（传统分页：通知是有页码语义的列表，不是无限滚动） */
  getList(params?: { page?: number; pageSize?: number }) {
    return request.get<PageData<NotificationItem>>('/users/me/notifications', { params })
  },

  /** 未读数（header 红点用，返回裸数字） */
  getUnreadCount() {
    return request.get<number>('/users/me/notifications/unread-count')
  },

  /**
   * 标记已读。
   *
   * ⚠️ 传的是**列表项的 id**（组内最新那条的"代表 id"），但后端会把它展开成**整组**已读——
   * 一条列表项可能代表"3 人赞了你的文章"，只标最新那条的话红点只减 1。
   */
  markRead(ids: Array<string | number>) {
    return request.post('/users/me/notifications/read', { ids })
  },

  /** 全部已读（不是"传空 ids"，是独立接口——不用隐式语义） */
  markAllRead() {
    return request.post('/users/me/notifications/read-all')
  },
}
