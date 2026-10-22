package com.hailin.blogsystem.entity.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 发起支付的结果（预支付凭证）。
 *
 * <p>真实支付里这个 VO 会包含渠道给的支付参数（prepay_id / paySign / nonceStr…）；
 * 虚拟支付只给 {@code payToken}，前端拿它打开「模拟支付页」。
 *
 * <p>{@code orderStatus} 用于幂等：订单已支付时也返回 200，
 * 让前端知道「不用再付了」，而不是把一次重复点击当失败。
 */
@Data
public class PaymentInitiateVO {

    private String orderNo;
    private String paymentNo;
    private String channel;
    private String payToken;
    private Long payAmount;
    private Long creditAmount;
    private String orderStatus;
    private LocalDateTime expireAt;
}
