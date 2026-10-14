/**
 * 钱包类型。
 *
 * 关于数字：后端 JacksonConfig 只把「字段名以 id 结尾的 Long」序列化成字符串
 * （雪花 ID 19 位，超出 JS 安全整数范围），其余 Long 一律保持 number。
 * 所以这里的 `id` 是 string，而 `balance` / `amount` 是 number。
 */

/** 钱包余额与进行中的预扣笔数 */
export interface WalletInfo {
  /** 当前余额（credit），**可能为负** */
  balance: number
  /**
   * 进行中的预扣笔数。
   *
   * 负余额有两种完全不同的含义，靠这个字段区分：
   * - `> 0`：额度被临时占用（调用还没结束），**不是欠款**
   * - `== 0`：结算后仍为负，才需要充值
   */
  pendingReserveCount: number
  /**
   * 进行中的预扣**总额**（credit）。
   *
   * 预扣按该路径硬上限占款（一次 Workflow 可能先占 10000、实际只花几十），
   * 所以展示时要用它把在途部分加回去——见 `utils/format.ts` 的 `displayCredit`。
   */
  pendingReserveCredit: number
  /**
   * 账单的**展示**保留期（天）。来自后端 `blog.wallet.bill-retention-days`，默认 7。
   *
   * 前端用它渲染「仅显示最近 N 天」——**不要把 7 硬编码进去**：
   * 后端调配置时前端不用跟着改，用户看到的说明也不会和实际口径对不上。
   */
  billRetentionDays: number
}

/** 充值套餐（来自后端 blog.wallet.packages 配置） */
export interface WalletPackage {
  code: string
  /** 应付金额，单位「分」——不是 credit，别和 `creditAmount` 混 */
  payAmount: number
  /** 到账额度（credit） */
  creditAmount: number
}

/**
 * 自定义充值的配置（来自后端 blog.wallet.custom）。
 *
 * ⚠️ `minYuan` / `maxYuan` 的单位是**元**，不是分——和后端字段名一样带 Yuan。
 * 前端不硬编码这两个数：改了后端配置前端不同步的话，用户按提示填了却被后端拒。
 */
export interface WalletCustomConfig {
  /** 单笔下限（元，整数） */
  minYuan: number
  /** 单笔上限（元，整数） */
  maxYuan: number
  /** 1 元 = 多少 credit */
  creditPerYuan: number
}

export type RechargeOrderStatus = 'PENDING' | 'PAID' | 'CANCELLED'

export interface RechargeOrder {
  orderNo: string
  packageCode: string
  payAmount: number
  creditAmount: number
  status: RechargeOrderStatus
  paidAt?: string | null
  createdAt?: string | null
}

/**
 * 账单条目（**聚合视角**）。
 *
 * 一次 AI 调用只会出现**一条净额**——预扣和退回是记账的实现细节，
 * 不应暴露给用户（用户要看的是「充了多少、花了多少」）。
 */
export interface WalletBillEntry {
  /** 业务主键 */
  bizId: string
  /** RECHARGE_ORDER = 充值到账 / SECKILL_ORDER = 秒杀到账 / AI_BILLING = AI 消费 */
  bizType: string
  /** 净额，有符号：正数入账、负数消费 */
  amount: number
  /** 该笔业务结算后的余额快照 */
  balanceAfter: number
  createdAt?: string | null
}
