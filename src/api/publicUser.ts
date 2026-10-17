import request from '@/utils/request'
import type { Article } from '@/types/article'
import type { PublicUserInfo, UserRelation } from '@/types/publicUser'
import type { PageData } from '@/types/result'

export const publicUserApi = {
  getInfo(id: number | string) {
    return request.get<PublicUserInfo>(`/users/${id}`)
  },
  getArticles(id: number | string, page: number, pageSize: number) {
    return request.get<PageData<Article>>(`/users/${id}/articles`, {
      params: { page, pageSize },
    })
  },
  getLiked(id: number | string, page: number, pageSize: number) {
    return request.get<PageData<Article>>(`/users/${id}/liked`, {
      params: { page, pageSize },
    })
  },
  getFavorited(id: number | string, page: number, pageSize: number) {
    return request.get<PageData<Article>>(`/users/${id}/favorited`, {
      params: { page, pageSize },
    })
  },
  getCommented(id: number | string, page: number, pageSize: number) {
    return request.get<PageData<Article>>(`/users/${id}/commented`, {
      params: { page, pageSize },
    })
  },
  getFollowing(id: number | string, page: number, pageSize: number) {
    return request.get<PageData<UserRelation>>(`/users/${id}/following`, {
      params: { page, pageSize },
    })
  },
  getFollowers(id: number | string, page: number, pageSize: number) {
    return request.get<PageData<UserRelation>>(`/users/${id}/followers`, {
      params: { page, pageSize },
    })
  },
  /**
   * 按昵称搜索用户（公开接口，游客可用）。
   *
   * 传统分页（page/pageSize + total），与关注/粉丝列表一致——
   * 用户量小，不需要游标。keyword 为空时后端返回空页而不是全站用户。
   */
  searchUsers(keyword: string, page: number, pageSize: number) {
    return request.get<PageData<UserRelation>>('/users/search', {
      params: { keyword, page, pageSize },
    })
  },
  follow(id: number | string) {
    return request.post(`/users/${id}/follow`)
  },
  cancelFollow(id: number | string) {
    return request.delete(`/users/${id}/follow`)
  },
}
