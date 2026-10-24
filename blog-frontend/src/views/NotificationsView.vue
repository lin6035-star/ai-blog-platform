<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useMessage } from 'naive-ui'
import { ArrowBack } from '@vicons/ionicons5'
import { notificationApi } from '@/api/notification'
import EmptyState from '@/components/common/EmptyState.vue'
import MainLayout from '@/layouts/MainLayout.vue'
import type { NotificationItem } from '@/types/notification'
import { formatArticleDate } from '@/utils/format'
import { unreadCount } from '@/utils/notificationBadge'
import {
  canOpenNotification,
  describeNotification,
  isCommentNotification,
  isUnreadNotification,
  notificationExcerpt,
} from '@/utils/notificationText'

defineOptions({ name: 'NotificationsView' })

const router = useRouter()
const message = useMessage()

const items = ref<NotificationItem[]>([])
const loading = ref(false)
const page = ref(1)
const pageSize = 20
const total = ref(0)
const pageCount = computed(() => Math.max(1, Math.ceil((total.value || 0) / pageSize)))

/** 「全部已读」按钮显不显示看**全站未读数**，不是本页——第 2 页还有未读时按钮也不该消失 */
const hasUnread = computed(() => unreadCount.value > 0)

async function load() {
  loading.value = true
  try {
    const result = await notificationApi.getList({ page: page.value, pageSize })
    items.value = result.data?.list ?? []
    total.value = result.data?.total ?? 0
  } catch (error) {
    message.error(error instanceof Error ? error.message : '消息加载失败')
  } finally {
    loading.value = false
  }
}

/**
 * 进页面**不再自动全部已读**（那是上一版的形态）。
 * 现在未读/已读是逐条维护的，进来就全标掉的话，未读标记和"全部已读"按钮都没有意义了。
 *
 * 但这里仍要拉一次未读数：header 那条拉取路径有 60 秒节流，不主动同步的话，
 * 红点会拿着进页面前的值，跟本页的未读标记对不上。
 */
async function refreshUnreadCount() {
  try {
    const result = await notificationApi.getUnreadCount()
    unreadCount.value = result.data ?? 0
  } catch {
    // 拉不到就沿用旧值，不影响浏览
  }
}

/**
 * 标记**一组**已读（`item.id` 是代表行的 id，后端按它展开整组）。
 *
 * 乐观更新：点一下要立刻看到未读标记消失、红点变少，不能等一次往返。
 * 失败要**回滚**——红点数字错了用户一眼就能看出来，静默失败比不更新更糟。
 */
async function markOneRead(item: NotificationItem) {
  const pendingCount = item.groupUnread ?? 0
  if (pendingCount <= 0) {
    return
  }

  item.groupUnread = 0
  unreadCount.value = Math.max(0, unreadCount.value - pendingCount)

  try {
    await notificationApi.markRead([item.id])
  } catch {
    item.groupUnread = pendingCount
    unreadCount.value += pendingCount
    message.error('标记已读失败，请稍后再试')
  }
}

async function markAllRead() {
  try {
    await notificationApi.markAllRead()
    items.value.forEach((entry) => {
      entry.groupUnread = 0
    })
    unreadCount.value = 0
  } catch (error) {
    message.error(error instanceof Error ? error.message : '标记失败，请稍后再试')
  }
}

// 返回上一页：优先走浏览器历史（back 不新增记录），直接 URL 进入时兜底跳个人中心。
// 与钱包 / 学习计划 / 秒杀那几个子页面同一套写法。
//
// ⚠️ 文案就是"返回"，不是"返回个人中心"：铃铛挂在**公共 header** 上，
// 从任何页面都能点进来，上一页可能是首页、文章详情、也可能是别人主页——
// 写死一个目的地是错的（这个按钮的实际行为也一直是"返回上一页"）
function goBack() {
  if (router.options.history.state.back) {
    router.back()
  } else {
    router.replace('/me')
  }
}

onMounted(async () => {
  await load()
  await refreshUnreadCount()
})

function onPageChange(next: number) {
  page.value = next
  load()
}

function openItem(item: NotificationItem) {
  // 先发起标记已读（本地是同步乐观更新），再跳转：反过来的话组件已经被路由切走，
  // 用户点回来时看到的还是"未读"
  void markOneRead(item)
  if (canOpenNotification(item)) {
    router.push(`/articles/${item.articleId}`)
  }
}
</script>

<template>
  <MainLayout>
    <section class="notifications-page">
      <header class="notifications-head">
        <button class="notifications-back" type="button" @click="goBack">
          <n-icon :component="ArrowBack" size="18" />
          返回
        </button>
        <div class="notifications-title-row">
          <h1>消息</h1>
          <button
            v-if="hasUnread"
            class="notifications-read-all"
            type="button"
            @click="markAllRead"
          >
            全部已读
          </button>
        </div>
        <p>别人对你的点赞、收藏、评论和关注都会汇总到这里</p>
      </header>

      <div v-if="loading && !items.length" class="notifications-loading">加载中…</div>

      <EmptyState
        v-else-if="!items.length"
        title="还没有消息"
        description="别人赞了、收藏了、评论了你的文章，或者关注了你，都会在这里提醒你"
      />

      <ul v-else class="notifications-list">
        <li
          v-for="item in items"
          :key="item.id"
          class="notification-item"
          :class="{
            'is-clickable': canOpenNotification(item),
            'is-unread': isUnreadNotification(item),
          }"
          @click="openItem(item)"
        >
          <n-avatar :size="38" round :src="item.actorAvatarUrl || undefined">
            <template #fallback>{{ (item.actorName || '某').slice(0, 1) }}</template>
          </n-avatar>

          <div class="notification-body">
            <p class="notification-text">{{ describeNotification(item) }}</p>
            <p v-if="isCommentNotification(item)" class="notification-excerpt">
              {{ notificationExcerpt(item) }}
            </p>
            <span class="notification-time">{{ formatArticleDate(item.createdAt) }}</span>
          </div>
        </li>
      </ul>

      <div v-if="pageCount > 1" class="notifications-pager">
        <n-pagination :page="page" :page-count="pageCount" @update:page="onPageChange" />
      </div>
    </section>
  </MainLayout>
</template>

<style scoped>
.notifications-page {
  max-width: 720px;
  margin: 0 auto;
  padding: 20px 20px 48px;
}

.notifications-head {
  display: grid;
  gap: 8px;
  margin-bottom: 18px;
}

/* 与 .wallet-back 同一套样式：子页面的返回按钮应该是同一个东西 */
.notifications-back {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  width: fit-content;
  padding: 6px 10px;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  background: #fff;
  color: #4b5563;
  font-size: 13px;
  cursor: pointer;
}

.notifications-back:hover {
  background: #f8fafc;
}

.notifications-title-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.notifications-title-row h1 {
  margin: 0;
  color: var(--color-ink);
  font-size: 22px;
}

.notifications-read-all {
  padding: 5px 10px;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  background: #fff;
  color: #4b5563;
  font-size: 13px;
  cursor: pointer;
}

.notifications-read-all:hover {
  background: #f8fafc;
}

.notifications-head p {
  margin: 0;
  color: var(--color-muted);
  font-size: 13px;
}

.notifications-loading {
  padding: 40px 0;
  color: var(--color-muted);
  text-align: center;
}

.notifications-list {
  margin: 0;
  padding: 0;
  list-style: none;
  background: #fff;
  border: 1px solid var(--color-border);
  border-radius: 12px;
  overflow: hidden;
}

.notification-item {
  display: flex;
  gap: 12px;
  align-items: flex-start;
  padding: 14px 16px;
}

.notification-item + .notification-item {
  border-top: 1px solid var(--color-border);
}

.notification-item.is-clickable {
  cursor: pointer;
  transition: background 0.18s ease;
}

.notification-item.is-clickable:hover {
  background: #f8fbfb;
}

/* 未读：左侧一条竖线 + 极浅底色。用 inset 阴影做竖线，不额外加元素——
   这一条可能代表一组（"3 人赞了"），所以未读是**整组**的判断 */
.notification-item.is-unread {
  background: #f4faf8;
  box-shadow: inset 3px 0 0 var(--color-forest);
}

.notification-body {
  min-width: 0;
  flex: 1;
}

.notification-text {
  margin: 0;
  color: var(--color-ink);
  line-height: 1.6;
  word-break: break-word;
}

.notification-excerpt {
  margin: 4px 0 0;
  color: var(--color-muted);
  line-height: 1.6;
  word-break: break-word;
}

.notification-time {
  display: inline-block;
  margin-top: 6px;
  color: var(--color-muted);
  font-size: 12px;
}

.notifications-pager {
  display: flex;
  justify-content: center;
  margin-top: 20px;
}
</style>
