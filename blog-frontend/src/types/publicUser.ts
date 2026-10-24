export interface PublicUserInfo {
  id: string
  nickname: string
  avatarUrl?: string
  bio?: string
  articlesCount: number
  followersCount: number
  followingCount: number
  followed: boolean
  self: boolean
  createdAt: string
}

export interface UserRelation {
  id: string
  nickname: string
  avatarUrl?: string
  bio?: string
  followed: boolean
  self: boolean
}
