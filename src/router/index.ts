import { createRouter, createWebHistory } from 'vue-router'
import { createDiscreteApi } from 'naive-ui'
import HomeView from '@/views/HomeView.vue'
import SearchView from '@/views/SearchView.vue'
import ArticleDetailView from '@/views/ArticleDetailView.vue'
import LoginView from '@/views/LoginView.vue'
import RegisterView from '@/views/RegisterView.vue'
import ForgotPasswordView from '@/views/ForgotPasswordView.vue'
import ResetPasswordView from '@/views/ResetPasswordView.vue'
import ProfileView from '@/views/ProfileView.vue'
import PublicProfileView from '@/views/PublicProfileView.vue'
import EditorView from '@/views/EditorView.vue'
import DraftsView from '@/views/DraftsView.vue'
import HotRankView from '@/views/HotRankView.vue'
import AuthCallbackView from '@/views/AuthCallbackView.vue'
import LearningPlansView from '@/views/LearningPlansView.vue'
import WalletView from '@/views/WalletView.vue'
import NotificationsView from '@/views/NotificationsView.vue'
import SeckillView from '@/views/SeckillView.vue'
import LearningPlanDetailView from '@/views/LearningPlanDetailView.vue'
import DevAgentRunsView from '@/views/DevAgentRunsView.vue'
import DevAgentRunDetailView from '@/views/DevAgentRunDetailView.vue'
import { useAuthStore } from '@/stores/auth'
import { setUnauthorizedHandler } from '@/utils/request'

const { message } = createDiscreteApi(['message'])

const router = createRouter({
  history: createWebHistory(),
  scrollBehavior(_to, _from, savedPosition) {
    if (savedPosition) {
      return savedPosition
    }

    return { top: 0 }
  },
  routes: [
    {
      path: '/',
      name: 'home',
      component: HomeView,
      meta: { title: '首页', keepAlive: true },
    },
    // 搜索独立成页：首页是「浏览信息流」（分类 + 推荐/最新/关注），
    // 搜索是「检索」（文章 / 用户两栏）。混在首页会让 HomeView 同时背两套语义。
    // 不加 requiresAuth：游客也能搜文章和用户
    {
      path: '/search',
      name: 'search',
      component: SearchView,
      meta: { title: '搜索' },
    },
    {
      path: '/articles/:id',
      name: 'article-detail',
      component: ArticleDetailView,
      meta: { title: '文章详情' },
    },
    {
      path: '/login',
      name: 'login',
      component: LoginView,
      meta: { title: '登录' },
    },
    {
      path: '/register',
      name: 'register',
      component: RegisterView,
      meta: { title: '注册' },
    },
    // 忘记密码：两个页面都**不需要登录**（忘了密码的人本来就登录不进来）
    {
      path: '/forgot-password',
      name: 'forgot-password',
      component: ForgotPasswordView,
      meta: { title: '忘记密码' },
    },
    {
      path: '/reset-password',
      name: 'reset-password',
      component: ResetPasswordView,
      meta: { title: '重置密码' },
    },
    {
      path: '/me',
      name: 'profile',
      component: ProfileView,
      meta: { title: '个人中心', requiresAuth: true },
    },
    {
      path: '/users/:id',
      name: 'public-profile',
      component: PublicProfileView,
      meta: { title: '用户主页' },
    },
    {
      path: '/me/learning-plans',
      name: 'learning-plans',
      component: LearningPlansView,
      meta: { title: '我的学习计划', requiresAuth: true },
    },
    {
      path: '/me/learning-plans/:id',
      name: 'learning-plan-detail',
      component: LearningPlanDetailView,
      meta: { title: '学习计划详情', requiresAuth: true },
    },
    {
      path: '/me/wallet',
      name: 'wallet',
      component: WalletView,
      meta: { title: '我的钱包', requiresAuth: true },
    },
    {
      path: '/me/notifications',
      name: 'notifications',
      component: NotificationsView,
      meta: { title: '消息', requiresAuth: true },
    },
    // 后端活动列表对游客开放，但前端页面要求登录：抢购本身必须登录，
    // 而「看得到抢不了」只会多出一次「点了才提示登录」的无效点击
    {
      path: '/seckill',
      name: 'seckill',
      component: SeckillView,
      meta: { title: '额度秒杀', requiresAuth: true },
    },
    {
      path: '/editor',
      name: 'editor-new',
      component: EditorView,
      meta: { title: '写文章', requiresAuth: true },
    },
    {
      path: '/editor/:id',
      name: 'editor-edit',
      component: EditorView,
      meta: { title: '编辑文章', requiresAuth: true },
    },
    {
      path: '/drafts',
      name: 'drafts',
      component: DraftsView,
      meta: { title: '草稿箱', requiresAuth: true },
    },
    {
      path: '/rank/hot',
      name: 'hot-rank',
      component: HotRankView,
      meta: { title: '热度榜' },
    },
    {
      path: '/auth/github/callback',
      name: 'github-oauth-callback',
      component: AuthCallbackView,
      meta: { title: 'GitHub 登录' },
    },
    // V4 第一刀：开发者只读面板。前端只隐藏入口，真正的门禁在后端白名单
    // （blog.ai.inspection.enabled + allowed-user-ids）——路由放行不等于有数据。
    {
      path: '/dev/agent-runs',
      name: 'dev-agent-runs',
      component: DevAgentRunsView,
      meta: { title: 'Agent 运行记录', requiresAuth: true },
    },
    {
      path: '/dev/agent-runs/:id',
      name: 'dev-agent-run-detail',
      component: DevAgentRunDetailView,
      meta: { title: 'Agent 运行详情', requiresAuth: true },
    },
  ],
})

/*
 * 未授权统一出口：token 失效（业务码 40100 / HTTP 401）时清登录态、提示、跳登录页。
 *
 * 注册在这里而不是 request.ts——utils 不能反向依赖 store / router（循环依赖，
 * 且模块顶层执行时 pinia 还没装上），而本文件已有 message 实例和 router。
 *
 * 在此之前，40100 的处理回调是个从未被注册的孤儿：token 过期后请求静默失败，
 * Pinia 里的用户名/头像原样留着，界面看起来"还登录着"，用户完全无感。
 */
let redirectingToLogin = false

setUnauthorizedHandler(() => {
  useAuthStore().clearAuth()

  const current = router.currentRoute.value
  // 已经在登录页（例如登录接口自身报未登录）不重复提示；
  // 并发请求同时 401 时，只由第一个触发跳转和提示
  if (current.path === '/login' || redirectingToLogin) {
    return
  }

  redirectingToLogin = true
  message.warning('登录已过期，请重新登录')
  router
    .push({ path: '/login', query: { redirect: current.fullPath } })
    .finally(() => {
      redirectingToLogin = false
    })
})

router.beforeEach((to) => {
  if (to.meta.requiresAuth && !useAuthStore().isLoggedIn) {
    message.warning('请先登录后再操作亲')
    // 跳登录页而不是 return false：后者会中止导航，
    // 直接刷新受保护页面（如 token 过期后刷新 /me）会停在空白页
    return { path: '/login', query: { redirect: to.fullPath } }
  }

  return true
})

router.afterEach((to, _from, failure) => {
  if (failure) return

  document.title = `${String(to.meta.title ?? '博客')} - 海林Blog`
})

export default router
