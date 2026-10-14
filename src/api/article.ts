import request from '@/utils/request'
import type { Article } from '@/types/article'
import type { PageData } from '@/types/result'

export interface ArticleListParams {
  /**
   * 游标分页：不传 = 第一页；否则原样带回上一页返回的 `nextCursor`。
   * **不要在前端拼这个值**——它是不透明 token，内部结构不属于接口契约。
   */
  cursor?: string
  pageSize?: number
  keyword?: string
  categoryId?: string
  sort?: ArticleSort
}

export type ArticleSort = 'recommend' | 'latest' | 'following'

export const articleApi = {
  getList(params?: ArticleListParams) {
    return request.get<PageData<Article>>('/articles', { params })
  },
  /** 热榜仍是 page/pageSize（数据源是 Redis ZSET 的排名区间，不是 SQL 分页） */
  getHotList(params?: { page?: number; pageSize?: number }) {
    return request.get<PageData<Article>>('/articles/hot', { params })
  },
  getDetail(id: number | string) {
    return request.get<Article>(`/articles/${id}`)
  },
  like(articleId: number | string) {
    return request.post(`/articles/${articleId}/like`)
  },
  unlike(articleId: number | string) {
    return request.delete(`/articles/${articleId}/like`)
  },
  favorite(articleId: number | string) {
    return request.post(`/articles/${articleId}/favorite`)
  },
  unfavorite(articleId: number | string) {
    return request.delete(`/articles/${articleId}/favorite`)
  },
  share(articleId: number | string) {
    return request.post(`/articles/${articleId}/share`)
  },
}
