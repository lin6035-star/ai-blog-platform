package com.hailin.blogsystem.wallet;

/**
 * 渠道侧的支付状态（主动查单的返回）。
 *
 * <p>与本地 {@code wallet_payment.status} 刻意分开：本地状态是本系统记录的，
 * 渠道状态是对方系统的事实。两者不一致正是对账要发现的东西，不能混用一个枚举。
 */
public enum ChannelPaymentStatus {
    /** 渠道侧仍待支付（用户还没付 / 状态未更新） */
    PENDING,
    /** 渠道侧已成功扣款 */
    SUCCESS,
    /** 渠道侧支付失败（余额不足、用户取消等） */
    FAILED
}
