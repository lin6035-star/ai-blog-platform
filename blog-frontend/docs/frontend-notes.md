# Frontend Notes

## 登录拦截统一规范：不跳转，只弹 toast

**决策时间：** 2026-07-13

**原则：** 任何需要登录的操作（API 请求、路由访问、按钮点击等），如果用户处于未登录状态，**一律不要跳转到 `/login` 页面**。改为弹出顶部提示信息，让用户自行决定是否登录。

**原因：** 直接跳转登录页体验割裂，打乱用户浏览节奏。用户看到提示后可以自己点右上角按钮去登录。

**已实施的改动：**

1. **路由守卫**（`src/router/index.ts`）— `requiresAuth` 路由被游客访问时，用 `createDiscreteApi` 弹出 toast 并 `return false` 取消导航
2. **评论区操作**（`src/components/comment/CommentSection.vue`）— 回复/点赞/删除/发布等操作，客户端判断未登录时调用 `showLoginTip()` 弹 toast
3. **CommentEditor**（`src/components/comment/CommentEditor.vue`）— 未登录点击"登录后发表评论"也弹 toast

**提示文案：** `'请先登录后再操作亲'`

**以后新增功能时的检查清单：**
- 不要在 `request.ts` 的 `onUnauthorized` 回调里跳转登录页
- 不要在组件里 `router.push('/login')` 除非用户主动点击登录按钮
- 需要登录才能触发的操作，先检查 `authStore.isLoggedIn`，未登录调用 `message.warning('请先登录后再操作亲')` 后 return
- `n-message-provider` 在 `App.vue` 中已全局注册，所有后代组件可以直接使用 `useMessage()`
- 路由守卫等非组件上下文需要 message 时，使用 `createDiscreteApi(['message'])`（见 `src/router/index.ts`）
