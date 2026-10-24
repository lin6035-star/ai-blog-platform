package com.hailin.blogsystem.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 充值支付单（渠道凭证）。
 *
 * 设计稿：docs/redis/充值订单真实化设计.md §2.1 / §4.2
 *
 * <p><b>为什么和订单分开</b>：订单是业务凭证（一次充值意图），支付单是渠道凭证
 * （一次支付尝试）。同一订单可以发起多次支付（取消后重试、换渠道），
 * 对账的对象是支付单而不是订单——只有它才和渠道账单一笔对一笔。
 */
@Data
@TableName("wallet_payment")
public class WalletPayment {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 商户支付单号（真实场景即 out_trade_no） */
    private String paymentNo;

    /** 对应 wallet_recharge_order.order_no */
    private String orderNo;

    /** 下单用户（冗余：回调没有登录态，靠它定位账本） */
    private Long userId;

    /** 支付渠道：MOCK /（将来 WECHAT / ALIPAY） */
    private String channel;

    /** 渠道交易号（回调回填） */
    private String channelTradeNo;

    /** 应付金额（分），下单时快照——回调金额只用来校验，不参与计算 */
    private Long payAmount;

    /** CREATED / SUCCESS / FAILED / CLOSED */
    private String status;

    /** 模拟支付页凭证（真实场景是 prepay_id） */
    private String payToken;

    private LocalDateTime paidAt;

    private String failReason;

    /** 原始回调报文（验签通过后落库，对账/纠纷用） */
    private String callbackPayload;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
