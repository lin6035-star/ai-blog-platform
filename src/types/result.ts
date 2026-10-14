export interface Result<T = unknown> {
  code: number
  message: string
  data: T
}

export interface PageData<T> {
  list: T[]
  /** 传统分页的总条数。**游标分页时为 null**——游标不需要它，算它要多一条 COUNT(*) */
  total?: number | null
  /** 传统分页的页码。游标分页时为 null */
  page?: number | null
  pageSize: number
  /**
   * 游标分页的「下一页」凭证；null / undefined = 已经到底。
   *
   * **不透明 token**：不要解析它的内容，也不要在前端拼，拿到就原样传回去。
   * 判断"还有没有下一页"看它，**不要看 total**。
   */
  nextCursor?: string | null
}
