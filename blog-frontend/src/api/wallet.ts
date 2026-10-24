import request from '@/utils/request'
import type { PageData } from '@/types/result'
import type {
  PaymentInitiate,
  RechargeOrder,
  WalletBillEntry,
  WalletCustomConfig,
  WalletInfo,
  WalletPackage,
} from '@/types/wallet'

/** 钱包接口（全部需要登录，路径不带 /api，由 request 的 baseURL 补） */
export const walletApi = {
  /** 当前余额 + 进行中的预扣笔数 */
  getWallet() {
    return request.get<WalletInfo>('/wallet')
  },

  /**
   * 账单（聚合视角）。
   *
   * 后端已按业务聚合：一次 AI 调用只有一条净额，不会出现「扣了 400 又退 365」的中间态。
   */
  getBill(page = 1, pageSize = 10) {
    return request.get<PageData<WalletBillEntry>>('/wallet/bill', {
      params: { page, pageSize },
    })
  },

  /** 充值套餐列表 */
  getPackages() {
    return request.get<WalletPackage[]>('/wallet/packages')
  },

  /**
   * 下单。
   *
   * ⚠️ 只传 packageCode：应付金额与到账额度都由后端按套餐决定。
   * 前端传金额等于自助发钱，接口层就不接受。
   */
  createRechargeOrder(packageCode: string) {
    return request.post<RechargeOrder>('/wallet/recharge/orders', { packageCode })
  },

  /** 自定义充值的上下限与兑换比例（只读配置，用来渲染输入提示） */
  getCustomConfig() {
    return request.get<WalletCustomConfig>('/wallet/recharge/custom-config')
  },

  /**
   * 自定义金额下单。
   *
   * ⚠️ 传的是**元**（整数），字段名 `payYuan` 而不是 `payAmount`：
   * 项目里 `payAmount` 一律是**分**，传错单位会让 5 元变成 5 分。
   * 到账额度仍由后端按配置算——前端只决定「充多少钱」。
   */
  createCustomRechargeOrder(payYuan: number) {
    return request.post<RechargeOrder>('/wallet/recharge/orders/custom', { payYuan })
  },

  /**
   * 发起支付（预支付）：创建/复用一条支付单，返回支付凭证。
   *
   * ⚠️ 调用后订单**仍是 PENDING**：真实支付里「发起支付」和「入账」之间隔着
   * 用户操作与渠道回调。入账只能等回调，前端不能把这里当成支付成功。
   */
  initiatePayment(orderNo: string) {
    return request.post<PaymentInitiate>(`/wallet/recharge/orders/${orderNo}/pay`)
  },

  /**
   * 模拟支付页确认付款（仅虚拟渠道）。
   *
   * 服务端会以渠道身份构造签名回调、走真实的回调链路（验签 / 幂等 / 金额校验），
   * 所以这个接口返回成功也不代表一定入账——以订单详情查询为准。
   */
  mockPay(orderNo: string, payToken: string) {
    return request.post<RechargeOrder>(`/wallet/recharge/orders/${orderNo}/mock-pay`, { payToken })
  },

  /** 订单详情（会触发惰性关单：已过期的订单直接返回 CLOSED） */
  getRechargeOrder(orderNo: string) {
    return request.get<RechargeOrder>(`/wallet/recharge/orders/${orderNo}`)
  },

  /** 我的充值订单（分页，按创建时间倒序） */
  listRechargeOrders(page = 1, pageSize = 10) {
    return request.get<PageData<RechargeOrder>>('/wallet/recharge/orders', {
      params: { page, pageSize },
    })
  },

  /** 用户取消待支付订单 */
  cancelRechargeOrder(orderNo: string) {
    return request.post<RechargeOrder>(`/wallet/recharge/orders/${orderNo}/cancel`)
  },

  /** 主动查单：回调丢失时手动补单（定时任务也会跑） */
  syncRechargeOrder(orderNo: string) {
    return request.post<boolean>(`/wallet/recharge/orders/${orderNo}/sync`)
  },
}
