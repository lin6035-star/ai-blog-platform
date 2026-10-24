import { useDialog } from 'naive-ui'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

/**
 * 未登录操作守卫 — 弹出对话框提醒登录，而非直接跳转。
 * 返回 check 函数：已登录返回 true，未登录弹出对话框返回 false。
 */
export function useLoginGuard() {
  const dialog = useDialog()
  const router = useRouter()
  const authStore = useAuthStore()

  function check(): boolean {
    if (authStore.isLoggedIn) {
      return true
    }

    dialog.warning({
      title: '请先登录',
      content: '登录后才可以执行该操作哦～',
      positiveText: '去登录',
      negativeText: '取消',
      onPositiveClick: () => {
        router.push('/login')
      },
    })

    return false
  }

  return { check }
}
