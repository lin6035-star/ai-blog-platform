package com.hailin.blogsystem.service;

/**
 * 秒杀活动状态流转。
 *
 * 设计稿：docs/redis/钱包与秒杀计划.md §6.6
 *
 * <p><b>先划清边界：抢购的正确性不归这里管。</b>时间窗和售罄是抢购脚本的 Lua
 * （{@code status ~= 'ACTIVE' or now < startAt or now >= endAt} + {@code stock <= 0}）
 * 和 DB 条件更新保证的，这一层只负责<b>运营视图</b>——
 * `DRAFT --到点--> ACTIVE(+预热)`、`ACTIVE --到期/售罄且已排空--> ENDED`。
 *
 * <p>所以这两个方法晚跑几秒没有任何代价，而<b>早跑（活动还没真正结束就置 ENDED）
 * 会丢掉在途事件</b>——所有取舍都朝这个方向偏。
 */
public interface SeckillLifecycleService {

    /**
     * 到点的 DRAFT → ACTIVE，并确保 Redis 状态已建立。
     *
     * @return 本轮真正流转的条数（CAS 输给别人的不算）
     */
    int activateDueActivities();

    /**
     * 到期或售罄、**且已排空**的 ACTIVE → ENDED。
     *
     * @return 本轮真正流转的条数
     */
    int endDueActivities();
}
