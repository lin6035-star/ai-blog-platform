<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useMessage } from 'naive-ui'
import {
  ArrowBack,
  ArrowUp,
  Contract,
  ChevronBack,
  ChevronForward,
  Eye,
  Expand,
  Heart,
  HeartOutline,
  PeopleOutline,
  Star,
  StarOutline,
  ShareSocialOutline,
} from '@vicons/ionicons5'
import { articleApi } from '@/api/article'
import type { ArticleAction } from '@/api/ai'
import { publicUserApi } from '@/api/publicUser'
import { useLoginGuard } from '@/composables/useLoginGuard'
import CommentSection from '@/components/comment/CommentSection.vue'
import MainLayout from '@/layouts/MainLayout.vue'
import { registerAiArticleActionHandler, unregisterAiArticleActionHandler } from '@/utils/aiArticleActionBus'
import { registerAiArticleResultHandler, unregisterAiArticleResultHandler } from '@/utils/aiArticleResultBus'
import type { AiArticleResultPayload } from '@/utils/aiArticleResultBus'
import type { Article } from '@/types/article'
import type { PublicUserInfo } from '@/types/publicUser'
import { formatArticleDateTime } from '@/utils/format'
import { renderArticleWithOutline } from '@/utils/articleOutline'

const route = useRoute()
const router = useRouter()
const message = useMessage()
const { check: requireLogin } = useLoginGuard()
const article = ref<Article | null>(null)
const loading = ref(false)
const liked = ref(false)
const favorited = ref(false)
const likeCount = ref(0)
const favoriteCount = ref(0)
const shareCount = ref(0)
const liking = ref(false)
const favoriting = ref(false)
const authorProfile = ref<PublicUserInfo | null>(null)
const authorProfileLoading = ref(false)
const followBusy = ref(false)
const detailBackBarRef = ref<HTMLElement | null>(null)
const isDetailBackBarStuck = ref(false)
const leftCollapsed = ref(false)
const rightCollapsed = ref(false)
const isImmersiveReading = computed(() => leftCollapsed.value && rightCollapsed.value)

const gridColumns = computed(() => {
  const left = leftCollapsed.value ? '0px' : '56px'
  const right = rightCollapsed.value ? '0px' : '260px'
  return `${left} minmax(0, 1fr) ${right}`
})

const renderedArticle = computed(() => {
  if (!article.value?.content) return null
  return renderArticleWithOutline(article.value.content)
})

const renderedContent = computed(() => {
  if (!renderedArticle.value) return ''
  return renderedArticle.value.html
})

const outlineItems = computed(() => {
  if (!renderedArticle.value) return []
  return renderedArticle.value.outline
})

const authorDisplayName = computed(() =>
  article.value?.authorName?.trim() || `#${article.value?.authorId}`,
)

const authorInitial = computed(() => authorDisplayName.value.slice(0, 1).toUpperCase())

async function loadAuthorProfile() {
  if (!article.value?.authorId) return
  const authorId = article.value.authorId
  authorProfileLoading.value = true
  authorProfile.value = null
  try {
    const result = await publicUserApi.getInfo(authorId)
    if (article.value?.authorId !== authorId) return
    authorProfile.value = result.data
  } catch {
    // 作者信息加载失败不阻塞页面
  } finally {
    if (article.value?.authorId === authorId) {
      authorProfileLoading.value = false
    }
  }
}

async function loadArticle() {
  loading.value = true
  authorProfile.value = null
  authorProfileLoading.value = false

  try {
    const result = await articleApi.getDetail(String(route.params.id))
    article.value = result.data
    liked.value = result.data.liked === 1
    favorited.value = result.data.favorited === 1
    likeCount.value = result.data.likeCount || 0
    favoriteCount.value = result.data.favoriteCount || 0
    shareCount.value = result.data.shareCount || 0
    loadAuthorProfile()
  } catch (error) {
    const msg = error instanceof Error ? error.message : '文章加载失败'
    message.error(msg)
  } finally {
    loading.value = false
  }
}

function goBack() {
  if (window.history.state?.back) {
    router.back()
    return
  }

  router.push({ path: '/', query: { refresh: String(Date.now()) } })
}

function scrollToTop() {
  window.scrollTo({ top: 0, behavior: 'smooth' })
}

function updateDetailBackBarStuck() {
  const bar = detailBackBarRef.value
  if (!bar) {
    isDetailBackBarStuck.value = false
    return
  }

  isDetailBackBarStuck.value = window.scrollY > 0 && bar.getBoundingClientRect().top <= 64
}

function scrollToOutlineItem(id: string) {
  document.getElementById(id)?.scrollIntoView({
    behavior: 'smooth',
    block: 'start',
  })
}

function toggleImmersiveReading() {
  const next = !isImmersiveReading.value
  leftCollapsed.value = next
  rightCollapsed.value = next
}

function goToAuthorProfile(tab?: string) {
  if (!article.value?.authorId) {
    return
  }

  router.push({ path: `/users/${article.value.authorId}`, query: tab ? { tab } : {} })
}

async function handleLike() {
  if (liking.value) return
  if (!requireLogin()) return

  liking.value = true
  const wasLiked = liked.value
  try {
    if (wasLiked) {
      await articleApi.unlike(article.value!.id)
      liked.value = false
      likeCount.value = Math.max(0, likeCount.value - 1)
    } else {
      await articleApi.like(article.value!.id)
      liked.value = true
      likeCount.value = likeCount.value + 1
    }
  } catch (error) {
    message.error(error instanceof Error ? error.message : '操作失败')
  } finally {
    liking.value = false
  }
}

async function handleAiArticleAction(action: ArticleAction) {
  if (!article.value) return

  // 评论由 CommentSection 处理，本组件不处理
  if (action.type === 'commentArticle') return

  if (String(article.value.id) !== String(action.articleId)) {
    message.warning('AI 操作的文章与当前文章不一致')
    return
  }

  if (action.type === 'scrollToTop') {
    window.scrollTo({ top: 0, behavior: 'smooth' })
    return
  }

  if (action.type === 'copyArticleLink') {
    await copyCurrentArticleLink()
    return
  }
}

async function handleAiArticleResults(payload: AiArticleResultPayload) {
  if (!article.value) return
  if (String(article.value.id) !== String(payload.articleId)) return
  if (!payload.results.length) return

  // 写操作已由后端批次执行；这里仅重新读取文章和作者的权威状态，禁止二次写入。
  await loadArticle()
}

async function handleFavorite() {
  if (favoriting.value) return
  if (!requireLogin()) return

  favoriting.value = true
  const wasFavorited = favorited.value
  try {
    if (wasFavorited) {
      await articleApi.unfavorite(article.value!.id)
      favorited.value = false
      favoriteCount.value = Math.max(0, favoriteCount.value - 1)
    } else {
      await articleApi.favorite(article.value!.id)
      favorited.value = true
      favoriteCount.value = favoriteCount.value + 1
    }
  } catch (error) {
    message.error(error instanceof Error ? error.message : '操作失败')
  } finally {
    favoriting.value = false
  }
}

async function copyCurrentArticleLink() {
  if (!article.value) return

  // 复制链接（Clipboard API 要求 HTTPS 或 localhost，否则降级到 execCommand）
  try {
    await navigator.clipboard.writeText(window.location.href)
  } catch {
    try {
      const textarea = document.createElement('textarea')
      textarea.value = window.location.href
      textarea.style.position = 'fixed'
      textarea.style.opacity = '0'
      document.body.appendChild(textarea)
      textarea.select()
      document.execCommand('copy')
      document.body.removeChild(textarea)
    } catch {
      message.error('分享失败，请手动复制地址栏链接')
      return
    }
  }

  // 通知后端记录分享（链接已复制，后端失败不影响提示）
  message.success('文章链接已复制')
  try {
    await articleApi.share(article.value.id)
    shareCount.value++
  } catch {
    // 后端接口暂未就绪时静默忽略
  }
}

async function handleAuthorFollow() {
  if (!authorProfile.value || authorProfile.value.self || followBusy.value) return
  if (!requireLogin()) return

  followBusy.value = true
  const wasFollowed = authorProfile.value.followed
  try {
    if (wasFollowed) {
      await publicUserApi.cancelFollow(authorProfile.value.id)
      authorProfile.value.followed = false
      authorProfile.value.followersCount = Math.max(0, (authorProfile.value.followersCount || 0) - 1)
      message.success('已取消关注')
    } else {
      await publicUserApi.follow(authorProfile.value.id)
      authorProfile.value.followed = true
      authorProfile.value.followersCount = (authorProfile.value.followersCount || 0) + 1
      message.success('关注成功')
    }
  } catch (error) {
    message.error(error instanceof Error ? error.message : '操作失败')
  } finally {
    followBusy.value = false
  }
}

onMounted(() => {
  loadArticle()
  nextTick(updateDetailBackBarStuck)
  window.addEventListener('scroll', updateDetailBackBarStuck, { passive: true })
  window.addEventListener('resize', updateDetailBackBarStuck)
  registerAiArticleActionHandler(handleAiArticleAction)
  registerAiArticleResultHandler(handleAiArticleResults)
})

// 详情页之间跳转（如点击 AI 引用片段）复用同一组件，onMounted 不会触发，
// 需要监听路由参数变化重新加载文章
watch(
  () => route.params.id,
  (newId, oldId) => {
    if (!newId || newId === oldId) return
    loadArticle()
    nextTick(() => {
      window.scrollTo(0, 0)
      updateDetailBackBarStuck()
    })
  },
)

onBeforeUnmount(() => {
  window.removeEventListener('scroll', updateDetailBackBarStuck)
  window.removeEventListener('resize', updateDetailBackBarStuck)
  unregisterAiArticleActionHandler(handleAiArticleAction)
  unregisterAiArticleResultHandler(handleAiArticleResults)
})
</script>

<template>
  <MainLayout>
    <div
      ref="detailBackBarRef"
      class="detail-back-bar"
      :class="{ stuck: isDetailBackBarStuck }"
    >
      <button class="back-link" type="button" @click="goBack">
        <n-icon><ArrowBack /></n-icon>
        返回
      </button>
    </div>

    <button
      v-if="article"
      class="detail-reading-mode-toggle"
      type="button"
      :class="{ active: isImmersiveReading }"
      :title="isImmersiveReading ? '退出沉浸' : '沉浸阅读'"
      :aria-pressed="isImmersiveReading"
      @click="toggleImmersiveReading"
    >
      <n-icon size="16">
        <Contract v-if="isImmersiveReading" />
        <Expand v-else />
      </n-icon>
      <span>{{ isImmersiveReading ? '退出沉浸' : '沉浸阅读' }}</span>
    </button>

    <div class="detail-layout" :style="{ gridTemplateColumns: gridColumns }">
      <aside class="detail-sidebar" :class="{ collapsed: leftCollapsed }">
        <button
          class="detail-action-btn"
          :class="{ active: liked }"
          :style="{ transitionDelay: '0ms' }"
          type="button"
          :disabled="liking"
          @click="handleLike"
        >
          <n-icon size="22">
            <Heart v-if="liked" />
            <HeartOutline v-else />
          </n-icon>
          <span>{{ likeCount || 0 }}</span>
        </button>
        <button
          class="detail-action-btn"
          :class="{ active: favorited }"
          :style="{ transitionDelay: '50ms' }"
          type="button"
          :disabled="favoriting"
          @click="handleFavorite"
        >
          <n-icon size="22">
            <Star v-if="favorited" />
            <StarOutline v-else />
          </n-icon>
          <span>{{ favoriteCount || 0 }}</span>
        </button>
        <button
          class="detail-action-btn share"
          :style="{ transitionDelay: '100ms' }"
          type="button"
          @click="copyCurrentArticleLink"
        >
          <n-icon size="22">
            <ShareSocialOutline />
          </n-icon>
          <span>{{ shareCount || 0 }}</span>
        </button>
        <button
          class="detail-action-btn"
          :style="{ transitionDelay: '150ms' }"
          type="button"
          title="回到顶部"
          @click="scrollToTop"
        >
          <n-icon size="22">
            <ArrowUp />
          </n-icon>
          <span>顶部</span>
        </button>

        <!-- 左侧折叠按钮 — 外挂在 sidebar 右边缘，收藏按钮旁边 -->
        <button
          class="detail-sidebar-toggle"
          type="button"
          :title="leftCollapsed ? '展开操作按钮' : '收起操作按钮'"
          @click="leftCollapsed = !leftCollapsed"
        >
          <n-icon size="13">
            <ChevronForward v-if="leftCollapsed" />
            <ChevronBack v-else />
          </n-icon>
        </button>
      </aside>

      <div class="detail-main">
        <n-spin :show="loading">
          <article class="detail-panel">
            <template v-if="article">
              <p class="eyebrow">ARTICLE</p>
              <h1>{{ article.title }}</h1>
              <div class="detail-meta">
                <button class="article-author-link" type="button" @click="goToAuthorProfile()">作者：{{ authorDisplayName }}</button>
                <span>{{ formatArticleDateTime(article.publishedAt, '未发布') }}</span>
                <span v-if="article.categoryName" class="article-category-tag">
                  #{{ article.categoryName }}
                </span>
                <span>
                  <n-icon><Eye /></n-icon>
                  {{ article.viewCount || 0 }}
                </span>
                <!-- 今日访客：与累计浏览量并列，必须带"今日"二字，否则会被读成累计值 -->
                <span v-if="article.uvCount">
                  <n-icon><PeopleOutline /></n-icon>
                  今日访客 {{ article.uvCount }}
                </span>
              </div>
              <div class="article-content" v-html="renderedContent" />
              <CommentSection
                v-if="article"
                :key="article.id"
                :article-id="article.id"
                :article-author-id="article.authorId"
                :comment-count="article.commentCount"
              />
            </template>
            <template v-else-if="!loading">
              <h1>文章不存在</h1>
              <p class="detail-summary">请返回首页查看其他公开文章。</p>
            </template>
          </article>
        </n-spin>
      </div>

      <aside v-if="article" class="detail-right-rail" :class="{ collapsed: rightCollapsed }">
        <div class="detail-right-rail-scroll">
        <section class="detail-author-card" :style="{ transitionDelay: '0ms' }">
          <div class="detail-author-profile">
            <div class="detail-author-avatar" @click="goToAuthorProfile()">
              <img v-if="authorProfile?.avatarUrl" :src="authorProfile.avatarUrl" class="detail-author-avatar-img" alt="" />
              <template v-else>{{ authorInitial }}</template>
            </div>
            <div class="detail-author-info">
              <p class="detail-author-label">本文作者</p>
              <h3>{{ authorDisplayName }}</h3>
            </div>
          </div>

          <div class="detail-author-stats">
            <div class="detail-author-stat-item" @click="goToAuthorProfile('articles')">
              <strong>{{ authorProfile?.articlesCount ?? '--' }}</strong>
              <span>文章</span>
            </div>
            <div class="detail-author-stat-item" @click="goToAuthorProfile('following')">
              <strong>{{ authorProfile?.followingCount ?? '--' }}</strong>
              <span>关注</span>
            </div>
            <div class="detail-author-stat-item" @click="goToAuthorProfile('followers')">
              <strong>{{ authorProfile?.followersCount ?? '--' }}</strong>
              <span>关注者</span>
            </div>
          </div>

          <div class="detail-author-actions">
            <span v-if="authorProfileLoading" class="detail-author-actions-placeholder" aria-hidden="true" />
            <button v-else-if="authorProfile && !authorProfile.self" type="button" :disabled="followBusy" @click="handleAuthorFollow">
              {{ followBusy ? '…' : authorProfile.followed ? '已关注' : '关注' }}
            </button>
            <span v-else-if="authorProfile?.self" class="detail-author-self">作者本人</span>
          </div>
        </section>

        <section class="detail-outline-card" :style="{ transitionDelay: '80ms' }">
          <div class="detail-outline-header">
            <h3>内容纲要</h3>
            <span>{{ outlineItems.length }} 节</span>
          </div>

          <nav v-if="outlineItems.length" class="detail-outline-list">
            <button
              v-for="item in outlineItems"
              :key="item.id"
              class="detail-outline-item"
              :class="`level-${item.level}`"
              type="button"
              @click="scrollToOutlineItem(item.id)"
            >
              {{ item.text }}
            </button>
          </nav>

          <p v-else class="detail-outline-empty">这篇文章暂时没有可生成目录的二级标题。</p>
        </section>

        </div><!-- .detail-right-rail-scroll -->

        <!-- 右侧折叠按钮 — 外挂在 right-rail 右边缘，名片和纲要之间 -->
        <button
          class="detail-right-rail-toggle"
          type="button"
          :title="rightCollapsed ? '展开侧边栏' : '收起侧边栏'"
          @click="rightCollapsed = !rightCollapsed"
        >
          <n-icon size="13">
            <ChevronBack v-if="rightCollapsed" />
            <ChevronForward v-else />
          </n-icon>
        </button>
      </aside>
    </div>
  </MainLayout>
</template>
