import { ref } from 'vue'

/**
 * header 铃铛红点的未读数——**跨组件共享的极小状态**。
 *
 * <p>为什么不放在 `AppHeader` 自己的 ref 里：消息页点进去会"全部已读"，
 * 那一刻红点必须立刻清零，而 AppHeader 无法感知另一个页面做了什么。
 * 如果只在 header 里存，用户从消息页返回来时红点还会亮着（要等下一次拉取），
 * 看起来像"点了已读没生效"。
 *
 * <p>也不为这个塞一个 pinia store：它就是一个数字，没有任何派生逻辑。
 */
export const unreadCount = ref(0)
