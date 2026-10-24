import request from '@/utils/request'
import type { PageData } from '@/types/result'
import type { Comment, CommentSort } from '@/types/comment'

export interface CommentListParams {
  page?: number
  pageSize?: number
  sort?: CommentSort
}

export interface CommentReplyListParams {
  page?: number
  pageSize?: number
}

export interface CommentCreatePayload {
  content: string
  parentId: string | null
}

export const commentApi = {
  getList(articleId: number | string, params?: CommentListParams) {
    return request.get<PageData<Comment>>(`/articles/${articleId}/comments`, {
      params,
    })
  },
  getReplies(rootId: number | string, params?: CommentReplyListParams) {
    return request.get<PageData<Comment>>(`/comments/${rootId}/replies`, {
      params,
    })
  },
  create(articleId: number | string, payload: CommentCreatePayload) {
    return request.post<void>(`/articles/${articleId}/comments`, payload)
  },
  delete(commentId: number | string) {
    return request.delete<void>(`/comments/${commentId}`)
  },
  like(commentId: number | string) {
    return request.post<void>(`/comments/${commentId}/like`)
  },
  unlike(commentId: number | string) {
    return request.delete<void>(`/comments/${commentId}/like`)
  },
}

export type { CommentSort }
