package com.hailin.blogsystem.entity.vo;

import lombok.Data;

/**
 * 钱包查询结果。
 *
 * 设计稿：docs/redis/钱包与秒杀计划.md §3「接口要能传达这个区分」
 *
 * <p><b>为什么不能只返回 balance</b>：余额为负有两种完全不同的含义，
 * 只看一个数字会误报——用户一发起调用就看到负余额，以为已经欠款了。
 *
 * <ul>
 *   <li><b>预占中</b>（{@code pendingReserveCount > 0}）：额度被临时占用，
 *       调用结束后按实际用量退回来，<b>不是欠款</b>，不该提示充值</li>
 *   <li><b>结算后仍为负</b>（{@code pendingReserveCount == 0}）：真的补不上，需要充值</li>
 * </ul>
 */
@Data
public class WalletVO {

    /** 当前余额（单位 credit），可能为负 */
    private Long balance;

    /** 进行中的预扣笔数（ai_billing_order.status = RESERVED） */
    private Long pendingReserveCount;

    /**
     * 进行中的预扣**总额**（credit）。
     *
     * <p><b>为什么要有金额而不只是笔数</b>：预扣是按该路径的硬上限占款的，
     * 一次 Workflow 可能先占掉 10000，而实际只花几十。只看 {@code balance}
     * 会让用户在任务执行期间看到一个远低于预期的数字（甚至负数），
     * 以为额度出错了——而它其实只是「在途预占」，结算后按实际用量退回。
     *
     * <p>所以展示层用它把在途部分加回去：<b>展示额度 = balance + pendingReserveCredit</b>。
     * 账务侧 {@code balance} 仍是权威事实，不做任何修改。
     */
    private Long pendingReserveCredit;

    /**
     * 账单的展示保留期（天）。
     *
     * <p>前端用它渲染「仅显示最近 N 天」，**不要去前端硬编码那个 7**——
     * 后端改了 {@code blog.wallet.bill-retention-days} 时，前端不用跟着改。
     */
    private Long billRetentionDays;
}
