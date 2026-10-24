export interface Comment {
  id: string
  articleId: string
  userId: string
  nickname: string
  avatarUrl?: string | null
  content: string
  rootId: string | null
  parentId: string | null
  replyToNickname?: string | null
  ipLocation?: string | null
  likeCount: number
  liked: boolean
  createdAt: string
  replies: Comment[]
  replyCount: number
}

export type CommentSort = 'time' | 'hot'
