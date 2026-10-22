package com.hailin.blogsystem.service;

import com.hailin.blogsystem.entity.WalletPayment;
import com.hailin.blogsystem.wallet.PaymentCallback;

/**
 * 充值回调处理内核（与具体渠道无关）。
 *
 * <p>真实支付里唯一可信的入账依据是「验签通过的渠道回调」；虚拟支付也走同一条路径，
 * 只是回调由 {@code MockPaymentChannel} 签出来。回调入口无登录态，所有判断必须自证。
 */
public interface WalletRechargeNotifyService {

    /**
     * 渠道异步回调入口：验签 → 防重放 → 幂等 → 交叉校验 → 金额校验 → 支付单 CAS → 订单 CAS → 入账。
     *
     * @param channel   渠道标识（与 {@code PaymentChannel#name()} 对应）
     * @param payload   回调报文<b>原文</b>（验签必须用原文）
     * @param signature HMAC 签名
     */
    void handleCallback(String channel, String payload, String signature);

    /**
     * 已通过可信来源（签名回调 / 服务端主动查单）确认渠道收款后，推进状态并入账。
     *
     * <p>返回 {@code true} 表示本次真正完成了入账；{@code false} 表示幂等跳过
     * （重复回调、并发已被别人处理、或订单已关闭）。
     */
    boolean applyChannelSuccess(WalletPayment payment, PaymentCallback callback);
}
