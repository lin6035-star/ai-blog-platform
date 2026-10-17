export interface Article {
  id: string
  categoryId: string
  authorId: string
  authorName?: string
  /**
   * 作者头像。可能为空（用户没上传过，或 GitHub 那边没给）——
   * 前端要用昵称首字兜底，不要指望它一定存在。
   */
  avatarUrl?: string
  categoryName?: string
  title: string
  summary: string
  content: string
  coverUrl: string
  status: number
  viewCount: number
  commentCount?: number
  likeCount?: number
  favoriteCount?: number
  shareCount?: number
  liked?: number
  favorited?: number
  /** 今日独立访客。注意与 viewCount 语义不同：viewCount 是累计浏览量，这个只统计当天 */
  uvCount?: number
  /**
   * 搜索命中的摘要/正文片段（含 `<em>` 标记）。**只在后端走 ES 检索时有值**：
   * ES 降级到 LIKE 时是 undefined（那时由 highlightByKeyword 兜底）。
   * 渲染前必须过 sanitizeHtml——ES 不转义源文本。
   */
  highlight?: string
  /** 搜索命中的标题高亮（含 `<em>` 标记）。与 highlight 同源同生命周期 */
  highlightTitle?: string
  publishedAt: string
  createdAt: string
  updatedAt: string
}
