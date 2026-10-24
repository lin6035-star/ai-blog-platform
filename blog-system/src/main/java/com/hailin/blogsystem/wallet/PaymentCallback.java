package com.hailin.blogsystem.wallet;

/**
 * 渠道回调解析后的通用结构。
 *
 * <p>不同渠道的报文格式不同，由各自的 {@link PaymentChannel#parseCallback(String)} 解析成这个结构；
 * 回调内核（验签之后的幂等 / 金额校验 / 状态推进）只认它，不认识任何具体渠道格式。
 *
 * @param paymentNo      商户支付单号
 * @param orderNo        业务订单号（用于和支付单交叉校验，防串单）
 * @param channelTradeNo 渠道交易号
 * @param amount         渠道侧金额（分）——只用于校验，不参与计算
 * @param timestamp      渠道报文时间戳（秒），用于防重放
 * @param rawPayload     回调原文（落库留证）
 */
public record PaymentCallback(
        String paymentNo,
        String orderNo,
        String channelTradeNo,
        long amount,
        long timestamp,
        String rawPayload
) {
}
