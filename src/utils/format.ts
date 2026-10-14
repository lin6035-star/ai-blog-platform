export function formatArticleDate(value: string) {
  if (!value) {
    return '未发布'
  }

  return new Intl.DateTimeFormat('zh-CN', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).format(new Date(value))
}

export function formatArticleDateTime(value: string, fallback = '') {
  if (!value) {
    return fallback
  }

  return new Intl.DateTimeFormat('zh-CN', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  }).format(new Date(value))
}

/**
 * 应付金额：后端用「分」，展示成元。
 *
 * ⚠️ 只用于 `payAmount` 这类真实货币口径的字段。
 * 额度（credit）不是钱，用下面的 formatCredit——两者混用会把 10000 credit 显示成 ¥100.00。
 */
export function formatPayAmount(fen: number | null | undefined) {
  if (fen === null || fen === undefined) {
    return '—'
  }

  return `¥${(fen / 100).toFixed(2)}`
}

/**
 * 额度（credit）展示：千分位整数，负数原样带符号。
 *
 * 余额允许为负（允许透支一轮），所以不能在这里把负号抹掉——
 * 抹掉之后「预占中」和「已欠费」看起来一模一样。
 */
export function formatCredit(credit: number | null | undefined) {
  if (credit === null || credit === undefined) {
    return '—'
  }

  return credit.toLocaleString('zh-CN')
}

/**
 * 展示用额度：把「在途预占」加回余额。
 *
 * 为什么需要它：预扣是按该路径的**硬上限**占款的——一次 Workflow 会先占掉 10000，
 * 而实际只花几十。于是任务执行期间余额会显示成远低于预期、甚至负数，
 * 用户看到的是"额度出错了"，其实那笔钱只是被临时占着，结算后按实际用量退回。
 *
 * 展示口径 = `balance + pendingReserveCredit`：
 * - 任务执行中：显示任务开始前的额度（在途预占被加回，数字不跳）
 * - 结算完成后：`pendingReserveCredit` 归零，显示真实扣减后的额度（一次性扣掉实际消费）
 * - 真欠费（`pendingReserveCount = 0` 且 `balance < 0`）：原样显示负数
 *
 * ⚠️ **只用于展示**。「够不够扣」一律由后端用 `balance` 判定；
 * 这里把预占加回来不代表那部分额度还能花——它已经被占住了。
 */
export function displayCredit(
  info: { balance: number; pendingReserveCredit?: number } | null | undefined,
) {
  if (!info) {
    return 0
  }

  return info.balance + (info.pendingReserveCredit ?? 0)
}

export function formatCommentTime(value: string) {
  if (!value) {
    return ''
  }

  const date = new Date(value)

  if (Number.isNaN(date.getTime())) {
    return value
  }

  const diff = Date.now() - date.getTime()
  const minute = 60 * 1000
  const hour = 60 * minute
  const day = 24 * hour

  if (diff >= 0 && diff < minute) {
    return '刚刚'
  }

  if (diff >= 0 && diff < hour) {
    return `${Math.floor(diff / minute)}分钟前`
  }

  if (diff >= 0 && diff < day) {
    return `${Math.floor(diff / hour)}小时前`
  }

  if (diff >= 0 && diff < 7 * day) {
    return `${Math.floor(diff / day)}天前`
  }

  return formatArticleDateTime(value)
}
