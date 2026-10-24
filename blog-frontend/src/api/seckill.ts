import request from '@/utils/request'
import type { SeckillActivity, SeckillResult } from '@/types/seckill'

/** 秒杀接口。活动列表公开（游客能看还剩多少），抢购与查结果需要登录 */
export const seckillApi = {
  listActivities() {
    return request.get<SeckillActivity[]>('/seckill/activities')
  },

  /**
   * 抢购。
   *
   * 返回 `QUEUED` 只代表**排上队了**——名额已占、事件已入队，DB 还没入账。
   * 拿到它之后必须轮询 {@link getResult}，别直接告诉用户「抢到了」。
   */
  grab(activityId: string) {
    return request.post<SeckillResult>(`/seckill/activities/${activityId}/grab`)
  },

  /**
   * 抢购结果（轮询用）。
   *
   * 结果通常在百毫秒内产出，所以只需每秒问几次，不需要 SSE——
   * 而且服务端给结果查询**不设限流**，正因为它是正常的高频行为。
   */
  getResult(activityId: string) {
    return request.get<SeckillResult>(`/seckill/activities/${activityId}/result`)
  },
}
