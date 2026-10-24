/**
 * 秒杀类型。
 *
 * 数字约定同 `wallet.ts`：后端只把「字段名以 id 结尾的 Long」序列化成字符串，
 * 其余 Long 保持 number。所以 `id` 是 string，库存与额度是 number。
 */

/**
 * 抢购状态（grab 的即时返回与 result 轮询共用一套取值）。
 *
 * **`QUEUED` 不是「抢到了」**——它只代表名额占住了、事件已入队，
 * 真正的入账由后端消费者异步完成。UI 上把这两者混为一谈，
 * 用户就会看到「抢到了」然后额度迟迟不到。
 */
export type SeckillStatus =
  /** 没参与过 */
  | 'NONE'
  /** 已占住名额，等待入账 */
  | 'QUEUED'
  /** 已到账，额度在钱包里 */
  | 'GRANTED'
  /** 售罄失败（终态） */
  | 'FAILED'
  /** 发放异常重试超限，需人工处理（终态） */
  | 'FAILED_RETRY'
  /* 下面是 grab 被当场拒绝的理由，不会出现在轮询结果里 */
  | 'SOLD_OUT'
  | 'DUPLICATE'
  | 'NOT_ACTIVE'

export interface SeckillActivity {
  id: string
  name: string
  totalStock: number
  /** 已发放（后端 DB 口径的最终账本） */
  soldCount: number
  /** 剩余可抢 */
  remainingStock: number
  /** 抢到发多少额度（credit） */
  creditAmount: number
  startAt: string
  endAt: string
  /** DRAFT / ACTIVE / ENDED */
  status: string
  /** 当前登录用户的参与状态；游客恒为 NONE */
  myStatus: SeckillStatus
}

export interface SeckillResult {
  status: SeckillStatus
  /** 给用户看的一句话；成功路径为空 */
  message?: string | null
  /** 仅 GRANTED 时有值：实际到账的额度 */
  creditAmount?: number | null
}
