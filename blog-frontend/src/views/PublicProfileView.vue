<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useMessage } from 'naive-ui'
import {
  ArrowBack,
  ChatbubbleOutline,
  DocumentTextOutline,
  Heart,
  PeopleOutline,
  Person,
  Star,
} from '@vicons/ionicons5'
import { articleApi } from '@/api/article'
import { publicUserApi } from '@/api/publicUser'
import ArticleCard from '@/components/article/ArticleCard.vue'
import AvatarPreviewModal from '@/components/common/AvatarPreviewModal.vue'
import EmptyState from '@/components/common/EmptyState.vue'
import { useLoginGuard } from '@/composables/useLoginGuard'
import MainLayout from '@/layouts/MainLayout.vue'
import type { Article } from '@/types/article'
import type { PublicUserInfo, UserRelation } from '@/types/publicUser'
import { formatArticleDate } from '@/utils/format'

const route = useRoute()
const router = useRouter()
const message = useMessage()
const { check: requireLogin } = useLoginGuard()

type PublicProfileTabKey = 'articles' | 'liked' | 'favorited' | 'commented' | 'following' | 'followers'
type RelationTabKey = 'following' | 'followers'

const activeTab = ref<PublicProfileTabKey>('articles')
const profile = ref<PublicUserInfo | null>(null)
const articles = ref<Article[]>([])
const relationUsers = ref<UserRelation[]>([])
const profileLoading = ref(false)
const articleLoading = ref(false)
const followBusy = ref(false)
const articleActionKeys = ref(new Set<string>())
const relationActionKeys = ref(new Set<string>())
const avatarPreviewShow = ref(false)
const currentPage = ref(1)
const pageSize = 5
const total = ref(0)

const routeUserId = computed(() => String(route.params.id ?? ''))
const joinedAtText = computed(() =>
  profile.value?.createdAt ? formatArticleDate(profile.value.createdAt) : '暂无',
)
const displayName = computed(() => profile.value?.nickname?.trim() || `#${routeUserId.value}`)
const activeTabInfo = computed(() => publicProfileTabs.find((tab) => tab.key === activeTab.value)!)
const isRelationTab = computed(() => isRelationTabKey(activeTab.value))

const publicProfileTabs = [
  { key: 'articles', label: '文章', icon: DocumentTextOutline, empty: '这个人还没有发布公开文章' },
  { key: 'liked', label: '喜欢', icon: Heart, empty: '这个人还没有喜欢过公开文章' },
  { key: 'favorited', label: '收藏', icon: Star, empty: '这个人还没有收藏过公开文章' },
  { key: 'commented', label: '评论过', icon: ChatbubbleOutline, empty: '这个人还没有评论过公开文章' },
  { key: 'following', label: '关注了', icon: PeopleOutline, empty: '这个人还没有关注其他用户' },
  { key: 'followers', label: '关注者', icon: PeopleOutline, empty: '这个人还没有关注者' },
] satisfies Array<{
  key: PublicProfileTabKey
  label: string
  icon: typeof DocumentTextOutline
  empty: string
}>

function isPublicProfileTabKey(key: unknown): key is PublicProfileTabKey {
  return typeof key === 'string' && publicProfileTabs.some((tab) => tab.key === key)
}

function isRelationTabKey(key: PublicProfileTabKey): key is RelationTabKey {
  return key === 'following' || key === 'followers'
}

function goBack() {
  if (window.history.state?.back) {
    router.back()
    return
  }

  router.push('/')
}

function getArticleTabRequest(key: PublicProfileTabKey, page: number) {
  if (key === 'liked') {
    return publicUserApi.getLiked(routeUserId.value, page, pageSize)
  }
  if (key === 'favorited') {
    return publicUserApi.getFavorited(routeUserId.value, page, pageSize)
  }
  if (key === 'commented') {
    return publicUserApi.getCommented(routeUserId.value, page, pageSize)
  }
  return publicUserApi.getArticles(routeUserId.value, page, pageSize)
}

function getRelationTabRequest(key: RelationTabKey, page: number) {
  if (key === 'followers') {
    return publicUserApi.getFollowers(routeUserId.value, page, pageSize)
  }
  return publicUserApi.getFollowing(routeUserId.value, page, pageSize)
}

async function loadProfile() {
  if (!routeUserId.value) {
    return
  }

  profileLoading.value = true
  try {
    const result = await publicUserApi.getInfo(routeUserId.value)
    profile.value = result.data
  } catch (error) {
    profile.value = null
    message.error(error instanceof Error ? error.message : '用户资料加载失败')
  } finally {
    profileLoading.value = false
  }
}

async function loadContentForTab(key: PublicProfileTabKey = activeTab.value, page = currentPage.value) {
  if (!routeUserId.value) {
    return
  }

  articleLoading.value = true
  try {
    if (isRelationTabKey(key)) {
      const result = await getRelationTabRequest(key, page)
      const data = result.data
      relationUsers.value = data.list ?? []
      articles.value = []
      total.value = data.total ?? 0
      currentPage.value = data.page ?? page
      return
    }

    const result = await getArticleTabRequest(key, page)
    const data = result.data
    articles.value = data.list ?? []
    relationUsers.value = []
    total.value = data.total ?? 0
    currentPage.value = data.page ?? page
  } catch (error) {
    articles.value = []
    relationUsers.value = []
    total.value = 0
    message.error(error instanceof Error ? error.message : '内容加载失败')
  } finally {
    articleLoading.value = false
  }
}

async function loadPublicProfile() {
  const initialTab = isPublicProfileTabKey(route.query.tab) ? route.query.tab : 'articles'
  activeTab.value = initialTab
  currentPage.value = 1
  articles.value = []
  relationUsers.value = []
  total.value = 0
  await Promise.all([loadProfile(), loadContentForTab(initialTab, 1)])
}

function switchTab(key: PublicProfileTabKey) {
  if (activeTab.value === key) {
    return
  }

  activeTab.value = key
  currentPage.value = 1
  articles.value = []
  relationUsers.value = []
  total.value = 0
  void loadContentForTab(key, 1)
}

function onPageChange(page: number) {
  void loadContentForTab(activeTab.value, page)
}

function goToArticle(article: Article) {
  router.push(`/articles/${article.id}`)
}

function goToAuthorProfile(authorId: string) {
  router.push(`/users/${authorId}`)
}

function goToUserProfile(userId: number | string) {
  router.push(`/users/${userId}`)
}

// requireLogin 由 useLoginGuard composable 提供，见上方

async function handleFollowToggle() {
  if (!profile.value || profile.value.self || followBusy.value) {
    return
  }

  if (!requireLogin()) {
    return
  }

  followBusy.value = true
  const wasFollowed = profile.value.followed
  try {
    if (wasFollowed) {
      await publicUserApi.cancelFollow(profile.value.id)
      profile.value.followed = false
      profile.value.followersCount = Math.max(0, (profile.value.followersCount || 0) - 1)
      message.success('已取消关注')
    } else {
      await publicUserApi.follow(profile.value.id)
      profile.value.followed = true
      profile.value.followersCount = (profile.value.followersCount || 0) + 1
      message.success('关注成功')
    }
  } catch (error) {
    message.error(error instanceof Error ? error.message : '操作失败')
  } finally {
    followBusy.value = false
  }
}

function beginRelationAction(key: string) {
  if (relationActionKeys.value.has(key)) {
    return false
  }

  relationActionKeys.value = new Set([...relationActionKeys.value, key])
  return true
}

function finishRelationAction(key: string) {
  const nextKeys = new Set(relationActionKeys.value)
  nextKeys.delete(key)
  relationActionKeys.value = nextKeys
}

async function handleRelationFollowToggle(user: UserRelation) {
  if (user.self) {
    return
  }

  if (!requireLogin()) {
    return
  }

  const actionKey = `relation-follow-${user.id}`
  if (!beginRelationAction(actionKey)) {
    return
  }

  const wasFollowed = user.followed
  try {
    if (wasFollowed) {
      await publicUserApi.cancelFollow(user.id)
      user.followed = false
      if (profile.value?.self && activeTab.value === 'following') {
        profile.value.followingCount = Math.max(0, (profile.value.followingCount || 0) - 1)
      }
    } else {
      await publicUserApi.follow(user.id)
      user.followed = true
      if (profile.value?.self && activeTab.value === 'following') {
        profile.value.followingCount = (profile.value.followingCount || 0) + 1
      }
    }
  } catch (error) {
    message.error(error instanceof Error ? error.message : '操作失败')
  } finally {
    finishRelationAction(actionKey)
  }
}

function beginArticleAction(key: string) {
  if (articleActionKeys.value.has(key)) {
    return false
  }

  articleActionKeys.value = new Set([...articleActionKeys.value, key])
  return true
}

function finishArticleAction(key: string) {
  const nextKeys = new Set(articleActionKeys.value)
  nextKeys.delete(key)
  articleActionKeys.value = nextKeys
}

async function handleArticleLike(article: Article) {
  if (!requireLogin()) {
    return
  }

  const actionKey = `like-${article.id}`
  if (!beginArticleAction(actionKey)) {
    return
  }

  const wasLiked = article.liked === 1
  try {
    if (wasLiked) {
      await articleApi.unlike(article.id)
      article.liked = 0
      article.likeCount = Math.max(0, (article.likeCount || 0) - 1)
    } else {
      await articleApi.like(article.id)
      article.liked = 1
      article.likeCount = (article.likeCount || 0) + 1
    }
  } catch (error) {
    message.error(error instanceof Error ? error.message : '操作失败')
  } finally {
    finishArticleAction(actionKey)
  }
}

async function handleArticleFavorite(article: Article) {
  if (!requireLogin()) {
    return
  }

  const actionKey = `favorite-${article.id}`
  if (!beginArticleAction(actionKey)) {
    return
  }

  const wasFavorited = article.favorited === 1
  try {
    if (wasFavorited) {
      await articleApi.unfavorite(article.id)
      article.favorited = 0
      article.favoriteCount = Math.max(0, (article.favoriteCount || 0) - 1)
    } else {
      await articleApi.favorite(article.id)
      article.favorited = 1
      article.favoriteCount = (article.favoriteCount || 0) + 1
    }
  } catch (error) {
    message.error(error instanceof Error ? error.message : '操作失败')
  } finally {
    finishArticleAction(actionKey)
  }
}

onMounted(loadPublicProfile)

watch(
  () => route.params.id,
  () => {
    void loadPublicProfile()
  },
)

watch(
  () => route.query.tab,
  (newTab) => {
    if (isPublicProfileTabKey(newTab)) {
      switchTab(newTab)
    }
  },
)
</script>

<template>
  <MainLayout>
    <button class="public-profile-back" type="button" @click="goBack">
      <n-icon><ArrowBack /></n-icon>
      <span>返回</span>
    </button>

    <div class="public-profile-layout">
      <div class="public-profile-top">
        <n-spin :show="profileLoading">
          <section class="public-profile-card">
            <template v-if="profile">
              <n-avatar :size="96" :src="profile.avatarUrl" class="public-profile-avatar" @click="avatarPreviewShow = true">
                <template #fallback>
                  <n-icon><Person /></n-icon>
                </template>
              </n-avatar>

              <div class="public-profile-identity">
                <h1>{{ displayName }}</h1>
                <p>{{ profile.bio || '这个人还没有写简介。' }}</p>
              </div>

              <div class="public-profile-actions">
                <n-button
                  v-if="profile.self"
                  type="primary"
                  secondary
                  @click="router.push('/me')"
                >
                  <template #icon><n-icon><Person /></n-icon></template>
                  我的中心
                </n-button>
                <n-button
                  v-else
                  type="primary"
                  :secondary="profile.followed"
                  :loading="followBusy"
                  @click="handleFollowToggle"
                >
                  <template #icon><n-icon><Person /></n-icon></template>
                  {{ profile.followed ? '已关注' : '关注' }}
                </n-button>
              </div>
            </template>

            <template v-else-if="!profileLoading">
              <div class="public-profile-missing">
                <h1>用户不存在</h1>
                <p>可以返回首页继续浏览其他文章。</p>
              </div>
            </template>
          </section>
        </n-spin>

        <aside class="public-profile-side">
          <div class="public-follow-row">
            <div>
              <span>关注了</span>
              <strong>{{ profile?.followingCount || 0 }}</strong>
            </div>
            <div>
              <span>关注者</span>
              <strong>{{ profile?.followersCount || 0 }}</strong>
            </div>
          </div>
          <div class="public-join-row">
            <span>加入于</span>
            <strong>{{ joinedAtText }}</strong>
          </div>
        </aside>
      </div>

      <section class="public-profile-main">
        <header class="public-tabs">
          <button
            v-for="tab in publicProfileTabs"
            :key="tab.key"
            class="public-tab"
            :class="{ active: activeTab === tab.key }"
            type="button"
            @click="switchTab(tab.key)"
          >
            <n-icon><component :is="tab.icon" /></n-icon>
            <span>{{ tab.label }}</span>
          </button>
        </header>

        <div v-if="articleLoading" class="public-article-skeletons">
          <div v-for="item in pageSize" :key="item" class="public-skeleton-row">
            <n-skeleton text style="width: 42%; height: 21px; margin-bottom: 10px" />
            <n-skeleton text style="margin-bottom: 8px" />
            <n-skeleton text style="width: 68%" />
          </div>
        </div>

        <div v-else-if="isRelationTab && relationUsers.length" class="public-relation-list">
          <div
            v-for="user in relationUsers"
            :key="user.id"
            class="public-relation-row"
            role="button"
            tabindex="0"
            @click="goToUserProfile(user.id)"
            @keydown.enter="goToUserProfile(user.id)"
          >
            <n-avatar :size="58" :src="user.avatarUrl" class="public-relation-avatar">
              <template #fallback>
                <n-icon><Person /></n-icon>
              </template>
            </n-avatar>

            <div class="public-relation-info">
              <h3>{{ user.nickname || `#${user.id}` }}</h3>
              <p>{{ user.bio || '这个人还没有写简介。' }}</p>
            </div>

            <n-button
              v-if="!user.self"
              type="primary"
              :secondary="user.followed"
              :loading="relationActionKeys.has(`relation-follow-${user.id}`)"
              @click.stop="handleRelationFollowToggle(user)"
            >
              {{ user.followed ? '已关注' : '关注' }}
            </n-button>
          </div>
        </div>

        <div v-else-if="articles.length" class="public-article-list">
          <ArticleCard
            v-for="article in articles"
            :key="article.id"
            :article="article"
            @open="goToArticle"
            @like="handleArticleLike"
            @favorite="handleArticleFavorite"
            @author-click="goToAuthorProfile"
          />
        </div>

        <EmptyState
          v-else
          :title="activeTabInfo.empty"
          :description="isRelationTab ? '可以点击用户进入他的个人中心。' : '这里只展示全站公开可见的内容。'"
        />

        <div v-if="total > pageSize" class="public-profile-pagination">
          <n-pagination
            :page="currentPage"
            :page-size="pageSize"
            :item-count="total"
            @update:page="onPageChange"
          />
        </div>
      </section>
    </div>

    <AvatarPreviewModal v-model:show="avatarPreviewShow" :src="profile?.avatarUrl ?? ''" />
  </MainLayout>
</template>

<style scoped>
.public-profile-back {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  width: min(calc(100% - 36px), 1200px);
  margin: 6px auto 0;
  padding: 0;
  border: 0;
  background: transparent;
  color: #2f6f73;
  font: inherit;
  font-weight: 700;
  cursor: pointer;
}

.public-profile-layout {
  display: grid;
  gap: 14px;
  max-width: 1200px;
  margin: 0 auto;
  padding: 10px 18px 56px;
}

.public-profile-top {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 300px;
  gap: 16px;
  align-items: stretch;
}

.public-profile-card,
.public-profile-side,
.public-profile-main {
  border: 1px solid #eef0f2;
  border-radius: 8px;
  background: #fff;
  box-shadow: 0 2px 8px rgba(15, 23, 42, 0.03);
}

.public-profile-card {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) 132px;
  align-items: center;
  gap: 22px;
  min-height: 172px;
  padding: 28px 32px;
}

.public-profile-avatar {
  flex-shrink: 0;
  cursor: pointer;
}

.public-profile-identity {
  min-width: 0;
}

.public-profile-identity h1 {
  margin: 0 0 12px;
  color: #111827;
  font-size: 30px;
  line-height: 1.25;
}

.public-profile-identity p {
  max-width: 560px;
  margin: 0;
  color: #5b6673;
  font-size: 15px;
  line-height: 1.8;
  word-break: break-word;
}

.public-profile-actions {
  display: grid;
  align-self: center;
}

.public-profile-missing {
  grid-column: 1 / -1;
}

.public-profile-missing h1 {
  margin: 0 0 8px;
  color: #111827;
  font-size: 26px;
}

.public-profile-missing p {
  margin: 0;
  color: #6b7280;
}

.public-profile-side {
  display: grid;
  grid-template-rows: auto 1fr;
  min-height: 172px;
  overflow: hidden;
}

.public-follow-row {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  border-bottom: 1px solid #eef0f2;
}

.public-follow-row div {
  display: grid;
  justify-items: center;
  gap: 6px;
  padding: 24px 12px;
}

.public-follow-row div + div {
  border-left: 1px solid #eef0f2;
}

.public-follow-row span,
.public-join-row span {
  color: #4b5563;
  font-size: 15px;
}

.public-follow-row strong {
  color: #1f2937;
  font-size: 20px;
  line-height: 1;
}

.public-join-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  padding: 20px;
}

.public-join-row strong {
  color: #1f2937;
  font-size: 15px;
  white-space: nowrap;
}

.public-profile-main {
  min-height: 520px;
  overflow: hidden;
}

.public-tabs {
  display: flex;
  align-items: center;
  gap: 28px;
  padding: 0 24px;
  border-bottom: 1px solid #eef0f2;
}

.public-tab {
  position: relative;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 18px 0 15px;
  border: 0;
  background: transparent;
  color: #6b7280;
  font-size: 15px;
  font-weight: 700;
  cursor: pointer;
  transition: color 0.16s;
}

.public-tab:hover,
.public-tab.active {
  color: #111827;
}

.public-tab.active::after {
  position: absolute;
  right: 0;
  bottom: -1px;
  left: 0;
  height: 3px;
  border-radius: 999px;
  background: #2f6f73;
  content: '';
}

.public-article-list {
  display: grid;
}

.public-relation-list {
  display: grid;
}

.public-relation-row {
  display: grid;
  grid-template-columns: 58px minmax(0, 1fr) 112px;
  align-items: center;
  gap: 22px;
  min-height: 112px;
  padding: 22px 28px;
  border-bottom: 1px solid #eef0f2;
  cursor: pointer;
  transition:
    background 0.16s,
    transform 0.16s;
}

.public-relation-row:hover {
  background: #fbfdfc;
}

.public-relation-row:focus-visible {
  outline: 2px solid rgba(47, 111, 115, 0.36);
  outline-offset: -2px;
}

.public-relation-avatar {
  flex-shrink: 0;
}

.public-relation-info {
  min-width: 0;
}

.public-relation-info h3 {
  margin: 0 0 8px;
  overflow: hidden;
  color: #111827;
  font-size: 18px;
  line-height: 1.35;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.public-relation-info p {
  margin: 0;
  overflow: hidden;
  color: #6b7280;
  font-size: 14px;
  line-height: 1.55;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.public-article-skeletons {
  display: grid;
  gap: 0;
}

.public-skeleton-row {
  padding: 20px;
  border-bottom: 1px solid #eef0f2;
}

.public-profile-pagination {
  display: flex;
  justify-content: center;
  padding: 24px 20px;
  border-top: 1px solid #eef0f2;
}

@media (max-width: 860px) {
  .public-profile-top {
    grid-template-columns: 1fr;
  }

  .public-profile-card {
    grid-template-columns: auto minmax(0, 1fr);
    grid-template-rows: auto auto;
    padding: 24px;
  }

  .public-profile-actions {
    grid-column: 1 / -1;
    width: 100%;
  }
}

@media (max-width: 560px) {
  .public-profile-layout {
    padding: 16px 14px 44px;
  }

  .public-profile-card {
    grid-template-columns: 1fr;
    justify-items: center;
    text-align: center;
  }

  .public-profile-identity p {
    max-width: 100%;
  }

  .public-tabs {
    gap: 20px;
    overflow-x: auto;
    padding: 0 16px;
  }

  .public-relation-row {
    grid-template-columns: 50px minmax(0, 1fr);
    gap: 14px;
    padding: 18px 16px;
  }

  .public-relation-row .n-button {
    grid-column: 2;
    justify-self: start;
  }
}
</style>
