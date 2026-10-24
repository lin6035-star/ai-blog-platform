<script setup lang="ts">
import { nextTick, onBeforeUnmount, watch } from 'vue'

/**
 * tianai-captcha 前端 SDK 是**全局脚本**（tac.min.js + tac.css），不是 npm 包，
 * 也没有官方类型定义——所以这里自己声明用到的那部分接口。
 */
interface TacInstance {
  init: () => void
  destroyWindow: () => void
  reloadCaptcha: () => void
}

interface TacConfig {
  requestCaptchaDataUrl: string
  validCaptchaUrl: string
  bindEl: string
  validSuccess: (res: { data?: { token?: string } }, c: unknown, tac: TacInstance) => void
  validFail: (res: unknown, c: unknown, tac: TacInstance) => void
  btnRefreshFun: (el: unknown, tac: TacInstance) => void
  btnCloseFun: (el: unknown, tac: TacInstance) => void
}

interface CaptchaWindow extends Window {
  initTAC?: (
    dir: string,
    config: TacConfig,
    style?: Record<string, unknown>,
  ) => Promise<TacInstance>
}

const props = defineProps<{ visible: boolean }>()
const emit = defineEmits<{
  (e: 'success', token: string): void
  (e: 'close'): void
  (e: 'error', message: string): void
}>()

/** 挂载点 id：SDK 会把它的模板 append 进这个元素 */
const MOUNT_ID = 'login-captcha-mount'
/** 官方提供的加载器（负责按需注入 tac 的 js/css），已自托管到 public 下，不依赖外部 CDN */
const LOADER_URL = '/static/js/load.min.js'
/** tac 产物目录：SDK 会去取 {dir}/js/tac.min.js 与 {dir}/css/tac.css */
const TAC_DIR = '/static/tac'

let tac: TacInstance | null = null
let loaderPromise: Promise<void> | null = null
let disposed = false

function loadLoader(): Promise<void> {
  if (loaderPromise) return loaderPromise

  loaderPromise = new Promise((resolve, reject) => {
    if ((window as CaptchaWindow).initTAC) {
      resolve()
      return
    }
    const script = document.createElement('script')
    script.src = LOADER_URL
    script.onload = () => resolve()
    script.onerror = () => reject(new Error('验证码组件加载失败，请刷新页面重试'))
    document.head.appendChild(script)
  })

  return loaderPromise
}

function destroy() {
  tac?.destroyWindow()
  tac = null
}

async function open() {
  // 等遮罩与挂载点真正渲染出来（SDK 靠 querySelector 找 bindEl，DOM 没到会直接抛错）
  await nextTick()
  await loadLoader()

  if (disposed) return

  const initTAC = (window as CaptchaWindow).initTAC
  if (!initTAC) {
    emit('error', '验证码组件未就绪，请刷新页面重试')
    return
  }

  destroy()

  tac = await initTAC(
    TAC_DIR,
    {
      // ⚠️ 这两个端点返回的是 tianai 原生 {code,msg,data}，不是项目统一的 Result，
      // 由 CaptchaController 专门保持——SDK 硬判定 data.code === 200（见该注释）
      requestCaptchaDataUrl: '/api/auth/captcha',
      validCaptchaUrl: '/api/auth/captcha/check',
      bindEl: `#${MOUNT_ID}`,
      validSuccess: (res, _c, t) => {
        t.destroyWindow()
        // 服务端把 captchaId 放在 data.token 里，它就是登录要带的一次性凭证
        emit('success', res?.data?.token ?? '')
      },
      validFail: (_res, _c, t) => {
        // 失败不关窗：凭证没拿到，让用户原地重滑，比关掉再弹一次顺
        t.reloadCaptcha()
      },
      btnRefreshFun: (_el, t) => t.reloadCaptcha(),
      btnCloseFun: (_el, t) => {
        t.destroyWindow()
        emit('close')
      },
    },
    // logoUrl = null 会隐藏 SDK 自带的 tianai logo（见 SDK 的 loadStyle：null 走 display:none）
    { logoUrl: null },
  )

  tac.init()
}

watch(
  () => props.visible,
  (visible) => {
    if (visible) {
      open().catch((e: unknown) => {
        emit('error', e instanceof Error ? e.message : '验证码加载失败')
        emit('close')
      })
    } else {
      // flush: 'pre' 保证这里先于 v-if 移除 DOM 执行——
      // 反过来会在 SDK 还持有已被删除的节点时调 destroyWindow
      destroy()
    }
  },
  { flush: 'pre' },
)

onBeforeUnmount(() => {
  disposed = true
  destroy()
})
</script>

<template>
  <!-- SDK 自己是 position:relative 的 318×318 块，不带遮罩也不居中，
       所以"弹窗"这层壳由我们提供 -->
  <div v-if="visible" class="captcha-mask">
    <div :id="MOUNT_ID" class="captcha-host" />
  </div>
</template>

<style scoped>
.captcha-mask {
  position: fixed;
  inset: 0;
  /* 必须压过登录页所有元素（.login-brand / .white-wipe 等）；SDK 内部是 997，留足余量 */
  z-index: 4000;
  display: flex;
  align-items: center;
  justify-content: center;
  background: rgba(0, 0, 0, 0.45);
}

.captcha-host {
  /* SDK 会把 #tianai-captcha-parent（318×318）append 到这里，自身不需要尺寸 */
  line-height: 0;
}
</style>
