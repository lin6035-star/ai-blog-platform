import { flushPromises, shallowMount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { NotificationItem } from '@/types/notification'
import { unreadCount } from '@/utils/notificationBadge'
import NotificationsView from './NotificationsView.vue'

/*
 * vi.mock 的工厂函数会被提升到 import 之前，所以共享的 spy 必须先经 vi.hoisted 建出来，
 * 不能在工厂里引用外层变量。
 */
const h = vi.hoisted(() => ({
  push: vi.fn(),
  replace: vi.fn(),
  back: vi.fn(),
  getList: vi.fn(),
  getUnreadCount: vi.fn(),
  markRead: vi.fn(),
  markAllRead: vi.fn(),
}))

vi.mock('vue-router', () => ({
  useRouter: () => ({
    push: h.push,
    replace: h.replace,
    back: h.back,
    // goBack 会读它判断"有没有上一页"
    options: { history: { state: { back: '/articles/1' } } },
  }),
}))

vi.mock('@/api/notification', () => ({
  notificationApi: {
    getList: h.getList,
    getUnreadCount: h.getUnreadCount,
    markRead: h.markRead,
    markAllRead: h.markAllRead,
  },
}))

/** 只替掉 useMessage（它要求 n-message-provider），其余 naive-ui 组件保持真实定义 */
vi.mock('naive-ui', async () => {
  const actual = await vi.importActual<Record<string, unknown>>('naive-ui')
  return {
    ...actual,
    useMessage: () => ({ error: vi.fn(), success: vi.fn(), warning: vi.fn() }),
  }
})

function item(patch: Partial<NotificationItem> = {}): NotificationItem {
  return {
    id: '1',
    type: 'ARTICLE_LIKED',
    actorId: '9',
    actorName: '张三',
    articleId: '10',
    articleTitle: 'Redis 实战',
    isRead: 0,
    groupUnread: 1,
    actorCount: 1,
    createdAt: '2026-10-11T20:31:00',
    ...patch,
  }
}

async function mountView(items: NotificationItem[]) {
  h.getList.mockResolvedValue({ data: { list: items, total: items.length } })
  h.getUnreadCount.mockResolvedValue({
    data: items.reduce((sum, entry) => sum + (entry.groupUnread ?? 0), 0),
  })

  const wrapper = shallowMount(NotificationsView, {
    global: {
      stubs: {
        // MainLayout 是子组件，shallowMount 默认会把它的插槽一起 stub 掉——必须放行 slot
        MainLayout: { template: '<div><slot /></div>' },
        'n-avatar': true,
        'n-pagination': true,
        'n-icon': true,
        EmptyState: true,
      },
    },
  })

  await flushPromises()
  return wrapper
}

describe('NotificationsView 的未读交互', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    unreadCount.value = 0
  })

  it('只有未读的组才带未读样式', async () => {
    const wrapper = await mountView([
      item({ id: '1', groupUnread: 2 }),
      item({ id: '2', groupUnread: 0 }),
    ])

    const rows = wrapper.findAll('.notification-item')
    expect(rows).toHaveLength(2)
    expect(rows[0].classes()).toContain('is-unread')
    expect(rows[1].classes()).not.toContain('is-unread')
    // header 红点跟着未读总数走
    expect(unreadCount.value).toBe(2)
  })

  it('点未读的一条：标记整组已读、红点减掉组内未读数', async () => {
    const wrapper = await mountView([
      item({ id: '7', groupUnread: 3, actorCount: 3 }),
      item({ id: '8', groupUnread: 1 }),
    ])
    expect(unreadCount.value).toBe(4)

    await wrapper.findAll('.notification-item')[0].trigger('click')
    await flushPromises()

    // 传的是**代表 id**，后端按它展开整组
    expect(h.markRead).toHaveBeenCalledWith(['7'])
    // 红点要一次减 3（不是减 1）——这就是 groupUnread 存在的意义
    expect(unreadCount.value).toBe(1)
    expect(wrapper.findAll('.notification-item')[0].classes()).not.toContain('is-unread')
    // 有文章标题 → 顺带跳详情
    expect(h.push).toHaveBeenCalledWith('/articles/10')
  })

  it('已读的那条不会被重复标记', async () => {
    const wrapper = await mountView([item({ id: '9', groupUnread: 0 })])

    await wrapper.findAll('.notification-item')[0].trigger('click')
    await flushPromises()

    expect(h.markRead).not.toHaveBeenCalled()
    expect(h.push).toHaveBeenCalledWith('/articles/10')
  })

  it('「全部已读」清空所有标记与红点', async () => {
    const wrapper = await mountView([item({ id: '1', groupUnread: 2 }), item({ id: '2', groupUnread: 1 })])
    expect(unreadCount.value).toBe(3)

    await wrapper.find('.notifications-read-all').trigger('click')
    await flushPromises()

    expect(h.markAllRead).toHaveBeenCalled()
    expect(unreadCount.value).toBe(0)
    expect(wrapper.findAll('.notification-item.is-unread')).toHaveLength(0)
    // 没有未读时按钮自己消失
    expect(wrapper.find('.notifications-read-all').exists()).toBe(false)
  })

  it('标记失败要回滚本地状态，不能静默把红点擦掉', async () => {
    h.markRead.mockRejectedValueOnce(new Error('boom'))
    const wrapper = await mountView([item({ id: '5', groupUnread: 2 })])

    await wrapper.findAll('.notification-item')[0].trigger('click')
    await flushPromises()

    // 红点数字错了用户一眼能看出来，所以失败必须把乐观更新撤销
    expect(unreadCount.value).toBe(2)
    expect(wrapper.findAll('.notification-item')[0].classes()).toContain('is-unread')
  })

  it('返回按钮走浏览器历史，文案是「返回」', async () => {
    const wrapper = await mountView([item()])

    const backButton = wrapper.find('.notifications-back')
    expect(backButton.text()).toContain('返回')
    expect(backButton.text()).not.toContain('个人中心')

    await backButton.trigger('click')
    expect(h.back).toHaveBeenCalled()
    expect(h.push).not.toHaveBeenCalledWith('/me')
  })
})
