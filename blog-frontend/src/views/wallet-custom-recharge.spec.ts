// @vitest-environment node
// 本文件只读源码文本做断言，不需要 DOM；跑在 jsdom 下会让 node:fs 被 externalize 而收集失败
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

const walletView = readFileSync(resolve(__dirname, 'WalletView.vue'), 'utf8')
const walletApi = readFileSync(resolve(__dirname, '../api/wallet.ts'), 'utf8')
const mockPayDialog = readFileSync(
  resolve(__dirname, '../components/wallet/MockPayDialog.vue'),
  'utf8',
)
const aiAssistant = readFileSync(resolve(__dirname, '../components/ai/AiAssistant.vue'), 'utf8')

describe('custom recharge', () => {
  it('sends payYuan and never payAmount', () => {
    // 字段名是唯一能看出单位的地方：这个项目里 payAmount 一律是「分」，
    // 写成 { payAmount: 5 } 会让用户想充 5 元、实际充了 5 分
    expect(walletApi).toContain('createCustomRechargeOrder(payYuan: number)')
    expect(walletApi).toContain(
      "request.post<RechargeOrder>('/wallet/recharge/orders/custom', { payYuan })",
    )
  })

  it('declares the amount as nullable because n-input-number clears to null', () => {
    // null 和 0 必须分开：用 !customYuan 之类的假值判断会把「没填」当成「填了 0」
    expect(walletView).toContain('ref<number | null>(null)')
    expect(walletView).toContain('yuan === null')
  })

  it('never formats the yuan amount with the fen-based formatter', () => {
    // formatPayAmount 吃「分」：formatPayAmount(5) 显示成 ¥0.05。
    // 而 5 元和 5 分看起来都"对"，要等付完钱才会有人发现——所以这里用源码断言钉死
    expect(mockPayDialog).not.toContain('formatPayAmount(customYuan')
    expect(mockPayDialog).not.toContain('formatPayAmount(yuan')
    // 模拟支付页直接用 customYuan 原值，不做任何换算
    expect(mockPayDialog).toContain('确认支付 ¥{{ customYuan }}')
  })

  it('uses the real two-phase payment flow instead of a custom payment path', () => {
    // 单开一条「自定义支付」会绕掉订单状态 CAS 与回调链路；
    // 改造后必须走：createOrder → initiatePayment → 模拟渠道确认 → 订单轮询
    expect(walletApi).toContain('initiatePayment(orderNo: string)')
    expect(walletApi).toContain('/wallet/recharge/orders/${orderNo}/pay')
    expect(walletApi).toContain('mockPay(orderNo: string, payToken: string)')
    expect(walletApi).toContain('/wallet/recharge/orders/${orderNo}/mock-pay')
    expect(walletApi).not.toContain('payCustomRechargeOrder')
    // 前端不能「点完就提示成功」：真实支付以回调后的订单状态为准（逻辑在共享弹窗里）
    expect(mockPayDialog).toContain('const finalOrder = await pollOrderStatus(order.orderNo)')
  })

  it('keeps the expire countdown from the order', () => {
    // 订单超时是真实支付必有的状态：前端必须展示倒计时，而不是等用户点了才报错
    expect(mockPayDialog).toContain('startCountdown')
    expect(mockPayDialog).toContain('支付剩余时间')
  })

  it('lets AI create the order but never pays on the user behalf', () => {
    // AI 只创建订单 + 发起支付，付款由用户在支付窗口确认。
    // 这条锁两个方向：AI 侧要能打开支付窗口；AI 侧不能直接调 mockPay。
    expect(aiAssistant).toContain('openPayDialog')
    expect(aiAssistant).toContain("view?.actionType === 'RECHARGE_WALLET'")
    expect(aiAssistant).toContain('<MockPayDialog')
    expect(aiAssistant).not.toContain('walletApi.mockPay')
  })

  it('loads the custom config outside the main Promise.all', () => {
    // 塞进 Promise.all 的话，「自定义充值配置没取到」会升级成「钱包页整个打不开」——
    // 滚动发布窗口期用户会连余额都看不见。失败时只把入口藏起来，套餐照常
    expect(walletView).toContain('walletApi.getCustomConfig()')
    expect(walletView).toContain('customEnabled.value = false')
  })

  it('guards both entry points before opening the pay dialog', () => {
    // paying 在开框前拦：第一个支付框还没结束时再点另一个入口，
    // 会叠开两个支付页 → 两张订单、两次到账。订单 CAS 防不了这个，它只能防「同一张订单付两次」
    const guards = walletView.match(/if \(paying\.value\) \{\s*return\s*\}/g) ?? []
    expect(guards.length).toBeGreaterThanOrEqual(2)
  })
})
