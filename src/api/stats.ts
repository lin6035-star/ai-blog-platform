import request from '@/utils/request'

/**
 * 站点活跃统计（**全站**口径，不是"我的活跃情况"）。
 *
 * 后端用「按天分的 Set + `SUNIONSTORE` 并集」实现，见 `UserActivityTracker`。
 *
 * ⚠️ **不要往 Bitmap 方向改**：本项目 `users.id` 是**雪花**（19 位、量级 2^63），
 * 远超位图单 key 的 offset 上限（2^32）。历史上按"自增主键"的错误假设实现过一版位图，
 * 配了 1 千万的 offset 防线，结果**真实用户全被拦掉、这个功能从未产出过数据**
 * （界面一直显示 0，且没有任何日志留痕）。这段注释原先写着"基于 Bitmap"，
 * 实现早就换成 Set 了，注释没跟上——比没注释更糟，因为会误导下一个人去"优化位图"。
 */
export interface ActivityStats {
  todayActive: number
  yesterdayActive: number
  /** 近 7 天活跃人数，**已去重**（不是 7 天相加） */
  weekActive: number
  retainedFromYesterday: number
  /** 昨日留存率；后端在"昨日无人活跃"时返回 null —— 是"算不出来"，不是 0% */
  yesterdayRetentionRate: number | null
}

export const statsApi = {
  getActivity() {
    return request.get<ActivityStats>('/stats/activity')
  },
}
