export const ARTICLE_STATUS = {
  DRAFT: 0,
  PUBLISHED: 1,
  HIDDEN: 2,
} as const

export type ArticleStatus = (typeof ARTICLE_STATUS)[keyof typeof ARTICLE_STATUS]

export function getArticleStatusLabel(status: number) {
  if (status === ARTICLE_STATUS.DRAFT) {
    return '草稿'
  }

  if (status === ARTICLE_STATUS.PUBLISHED) {
    return '已发布'
  }

  return '已隐藏'
}
