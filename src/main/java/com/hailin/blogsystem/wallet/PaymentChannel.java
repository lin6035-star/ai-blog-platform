package com.hailin.blogsystem.wallet;

import com.hailin.blogsystem.entity.WalletPayment;

/**
 * 支付渠道抽象。
 *
 * <p><b>为什么要有这一层</b>：真实支付里「下单」和「支付」之间隔着渠道（微信 / 支付宝）。
 * 订单状态机、回调内核、对账、关单都不应该知道渠道细节；
 * 接真实渠道时只新增一个实现，其余代码一行不改。
 *
 * <p>虚拟支付（{@link MockPaymentChannel}）把这些能力都模拟出来，只是没有真实网络：
 * 预支付凭证、渠道交易号、签名回调、主动查单。
 */
public interface PaymentChannel {

    /** 渠道标识：MOCK /（将来 WECHAT / ALIPAY） */
    String name();

    /**
     * 发起支付：生成预支付凭证。
     *
     * <p>真实渠道会在这里调「统一下单」接口，拿到 {@code prepay_id} 和支付参数；
     * 这里只生成一个模拟支付页用的 {@code payToken}。
     *
     * @return 给前端用的支付凭证
     */
    String initiate(WalletPayment payment);

    /**
     * 主动查单：回调可能丢失 / 延迟，补单任务靠它把渠道事实拉回来。
     *
     * <p>真实渠道会发 HTTP 查询；虚拟渠道的「渠道侧」就是本系统，所以直接映射本地状态。
     */
    ChannelPaymentStatus query(WalletPayment payment);

    /**
     * 模拟用户在渠道内完成支付，返回渠道签名后的回调报文。
     *
     * <p><b>只有虚拟渠道实现这个方法</b>——真实支付里用户操作发生在渠道侧，
     * 服务端不可能替用户付款。它的存在只是为了让回调链路在本地能被完整演练。
     */
    SignedCallback simulatePaidCallback(WalletPayment payment, String payToken);

    /**
     * 验签：对回调<b>原文</b>做 HMAC 比对。
     *
     * <p>调用方必须把渠道收到的原始 payload 原样传进来，不能反序列化后重新拼——
     * 字段顺序 / 空白任何一处不同都会让合法回调被误拒。
     */
    boolean verifyCallback(String payload, String signature);

    /** 把渠道格式的报文解析成通用结构；格式不合法应抛 {@link IllegalArgumentException} */
    PaymentCallback parseCallback(String payload);
}
