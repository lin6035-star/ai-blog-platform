package com.hailin.blogsystem.service.impl;

import com.hailin.blogsystem.config.BlogWalletProperties;
import com.hailin.blogsystem.constants.BlogConstants;
import com.hailin.blogsystem.entity.WalletPayment;
import com.hailin.blogsystem.entity.WalletRechargeOrder;
import com.hailin.blogsystem.exception.BusinessException;
import com.hailin.blogsystem.mapper.WalletPaymentMapper;
import com.hailin.blogsystem.mapper.WalletRechargeOrderMapper;
import com.hailin.blogsystem.service.WalletRechargeNotifyService;
import com.hailin.blogsystem.service.WalletService;
import com.hailin.blogsystem.wallet.PaymentCallback;
import com.hailin.blogsystem.wallet.PaymentChannel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 回调内核实现。
 *
 * <p><b>步骤顺序是刻意的，不能调换</b>：
 * <ol>
 *   <li>验签——伪回调必须在任何读写之前被拒</li>
 *   <li>防重放——时间戳超窗拒绝</li>
 *   <li>幂等——重复投递直接成功返回（渠道会重试，不能让它以为失败）</li>
 *   <li>交叉校验——支付单 / 订单号 / 金额三方对齐，防串单与篡改</li>
 *   <li>支付单 CAS → 订单 CAS → 入账——同一事务，要么都成功要么都不落</li>
 * </ol>
 *
 * <p><b>「关单后到账」为什么返回成功</b>：渠道已经收了钱，我们这边的订单却被超时关掉了。
 * 这时不能回滚支付单（渠道事实就是成功），也不该自动入账（订单已关闭）。
 * 正确做法是记录 ERROR 留给对账/人工处理——真实支付里这是最贵的一类不一致。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WalletRechargeNotifyServiceImpl implements WalletRechargeNotifyService {

    private static final String STATUS_SUCCESS = BlogConstants.Wallet.PAYMENT_SUCCESS;
    private static final String STATUS_CREATED = BlogConstants.Wallet.PAYMENT_CREATED;

    private final WalletPaymentMapper walletPaymentMapper;
    private final WalletRechargeOrderMapper walletRechargeOrderMapper;
    private final WalletService walletService;
    private final BlogWalletProperties walletProperties;
    private final PaymentChannel paymentChannel;

    @Override
    @Transactional
    public void handleCallback(String channel, String payload, String signature) {
        if (!paymentChannel.name().equals(channel)) {
            throw new BusinessException(BlogConstants.ErrorCode.BAD_REQUEST, "不支持的支付渠道：" + channel);
        }
        if (!paymentChannel.verifyCallback(payload, signature)) {
            log.error("[RECHARGE-NOTIFY] 回调验签失败，已拒绝 channel={}", channel);
            throw new BusinessException(BlogConstants.ErrorCode.FORBIDDEN, "回调验签失败");
        }

        PaymentCallback callback = paymentChannel.parseCallback(payload);
        long nowSeconds = System.currentTimeMillis() / 1000;
        long tolerance = walletProperties.getPayment().getCallbackToleranceSeconds();
        if (Math.abs(nowSeconds - callback.timestamp()) > tolerance) {
            log.error("[RECHARGE-NOTIFY] 回调时间戳超出容忍窗口，已拒绝（疑似重放）：paymentNo={} ts={} now={}",
                    callback.paymentNo(), callback.timestamp(), nowSeconds);
            throw new BusinessException(BlogConstants.ErrorCode.FORBIDDEN, "回调已过期");
        }

        WalletPayment payment = walletPaymentMapper.selectByPaymentNo(callback.paymentNo());
        if (payment == null) {
            log.error("[RECHARGE-NOTIFY] 回调指向不存在的支付单，疑似伪回调/串单：paymentNo={}",
                    callback.paymentNo());
            throw new BusinessException(BlogConstants.ErrorCode.NOT_FOUND, "支付单不存在");
        }
        applyChannelSuccess(payment, callback);
    }

    @Override
    @Transactional
    public boolean applyChannelSuccess(WalletPayment payment, PaymentCallback callback) {
        // ① 幂等：渠道重复投递必须回成功，否则它会一直重试
        if (STATUS_SUCCESS.equals(payment.getStatus())) {
            log.info("[RECHARGE-NOTIFY] 重复回调，幂等返回：paymentNo={}", payment.getPaymentNo());
            return false;
        }
        if (!STATUS_CREATED.equals(payment.getStatus())) {
            log.warn("[RECHARGE-NOTIFY] 支付单已是终态，忽略回调：paymentNo={} status={}",
                    payment.getPaymentNo(), payment.getStatus());
            return false;
        }

        // ② 交叉校验：支付单与回调必须指向同一订单
        if (!payment.getOrderNo().equals(callback.orderNo())) {
            log.error("[RECHARGE-NOTIFY] 回调订单号与支付单不一致，拒绝入账：paymentNo={} callbackOrder={} paymentOrder={}",
                    payment.getPaymentNo(), callback.orderNo(), payment.getOrderNo());
            throw new BusinessException(BlogConstants.ErrorCode.CONFLICT, "回调订单号不匹配");
        }

        // ③ 金额校验：以支付单快照为准，回调金额只用于比对，绝不参与计算
        if (callback.amount() != payment.getPayAmount()) {
            log.error("[RECHARGE-NOTIFY] 回调金额与支付单不一致，拒绝入账：paymentNo={} callbackAmount={} expected={}",
                    payment.getPaymentNo(), callback.amount(), payment.getPayAmount());
            throw new BusinessException(BlogConstants.ErrorCode.BAD_REQUEST, "回调金额不匹配");
        }

        // ④ 支付单 CAS：并发回调只有一个能推进
        int updated = walletPaymentMapper.markSuccess(
                payment.getPaymentNo(), callback.channelTradeNo(), callback.rawPayload());
        if (updated == 0) {
            WalletPayment latest = walletPaymentMapper.selectByPaymentNo(payment.getPaymentNo());
            if (latest != null && STATUS_SUCCESS.equals(latest.getStatus())) {
                log.info("[RECHARGE-NOTIFY] 并发回调已被另一条处理，幂等返回：paymentNo={}",
                        payment.getPaymentNo());
                return false;
            }
            throw new BusinessException(BlogConstants.ErrorCode.CONFLICT, "支付单状态已变更，请重试");
        }

        // ⑤ 订单 CAS
        int paid = walletRechargeOrderMapper.markPaid(payment.getOrderNo());
        if (paid == 0) {
            WalletRechargeOrder latest = walletRechargeOrderMapper.selectByOrderNoForUpdate(payment.getOrderNo());
            if (latest != null && BlogConstants.Wallet.ORDER_PAID.equals(latest.getStatus())) {
                log.info("[RECHARGE-NOTIFY] 订单已 PAID（并发回调），幂等返回：orderNo={}",
                        payment.getOrderNo());
                return false;
            }
            log.error("[RECHARGE-NOTIFY] 关单后到账！渠道已收款但订单不是 PENDING，需人工处理："
                            + "paymentNo={} orderNo={} orderStatus={} credit={}",
                    payment.getPaymentNo(), payment.getOrderNo(),
                    latest == null ? "NULL" : latest.getStatus(),
                    latest == null ? null : latest.getCreditAmount());
            // 不回滚支付单：渠道事实就是成功，回滚会让它和渠道账单永久对不上。
            return false;
        }

        // ⑥ 入账（幂等键用订单号：一个订单只入账一次，与支付单尝试次数无关）
        WalletRechargeOrder order = walletRechargeOrderMapper.selectByOrderNoForUpdate(payment.getOrderNo());
        walletService.increase(
                order.getUserId(),
                order.getCreditAmount(),
                BlogConstants.Wallet.TX_RECHARGE,
                BlogConstants.Wallet.BIZ_RECHARGE_ORDER,
                order.getOrderNo(),
                "RECHARGE:" + order.getOrderNo(),
                rechargeRemark(order));

        log.info("[RECHARGE-NOTIFY] 回调入账成功 orderNo={} paymentNo={} credit=+{}",
                order.getOrderNo(), payment.getPaymentNo(), order.getCreditAmount());
        return true;
    }

    /**
     * 流水备注（文案与改造前保持一致，历史流水可继续按同样口径阅读）。
     *
     * <p>自定义订单没有套餐码，直接拼会得到「充值套餐 CUSTOM」——一句对账时没人看得懂的话，
     * 所以按金额说人话。
     */
    private String rechargeRemark(WalletRechargeOrder order) {
        if (BlogConstants.Wallet.CUSTOM_PACKAGE_CODE.equals(order.getPackageCode())) {
            return "自定义充值 " + order.getPayAmount() / 100 + " 元";
        }
        return "充值套餐 " + order.getPackageCode();
    }
}
