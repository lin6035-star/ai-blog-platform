package com.hailin.blogsystem.service.impl;

import com.hailin.blogsystem.constants.BlogConstants;
import com.hailin.blogsystem.entity.WalletPayment;
import com.hailin.blogsystem.entity.WalletRechargeOrder;
import com.hailin.blogsystem.mapper.WalletPaymentMapper;
import com.hailin.blogsystem.mapper.WalletRechargeOrderMapper;
import com.hailin.blogsystem.wallet.ChannelPaymentStatus;
import com.hailin.blogsystem.wallet.PaymentChannel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 超时订单关闭（独立 bean：让 {@code @Transactional} 真正生效）。
 *
 * <p>为什么不放在 {@code WalletRechargeServiceImpl} 里：惰性关单会在
 * {@code initiatePayment / getOrder / listOrders} 里被调用，同类自调用不走代理，
 * 事务注解会静默失效——关单失败时订单状态和支付单状态会不一致。
 *
 * <p><b>关单前必须查渠道</b>（真实支付的硬要求）：本地先关、渠道其实已经收款的场景真实存在，
 * 结果是「用户付了钱、订单却关了」。虚拟渠道的查单目前返回 PENDING，
 * 但这条路径被真实执行，接真实渠道时不用改关单逻辑。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WalletRechargeCloseService {

    private final WalletRechargeOrderMapper walletRechargeOrderMapper;
    private final WalletPaymentMapper walletPaymentMapper;
    private final PaymentChannel paymentChannel;

    /**
     * 关闭一笔已过期的待支付订单（幂等：已不是 PENDING / 未过期直接返回 false）。
     *
     * @return 本次是否真的关闭了订单
     */
    @Transactional
    public boolean closeIfExpired(WalletRechargeOrder order) {
        if (order == null
                || !BlogConstants.Wallet.ORDER_PENDING.equals(order.getStatus())
                || order.getExpireAt() == null
                || order.getExpireAt().isAfter(LocalDateTime.now())) {
            return false;
        }

        // 关单前查渠道：渠道已收款 → 不能关，等补单任务把账补上
        for (WalletPayment payment : walletPaymentMapper.selectByOrderNo(order.getOrderNo())) {
            if (!BlogConstants.Wallet.PAYMENT_CREATED.equals(payment.getStatus())) {
                continue;
            }
            if (paymentChannel.query(payment) == ChannelPaymentStatus.SUCCESS) {
                log.warn("[RECHARGE-CLOSE] 渠道已收款，跳过关单等待补单 orderNo={} paymentNo={}",
                        order.getOrderNo(), payment.getPaymentNo());
                return false;
            }
        }

        int changed = walletRechargeOrderMapper.markClosed(
                order.getOrderNo(), BlogConstants.Wallet.CLOSE_TIMEOUT);
        if (changed == 0) {
            // 并发下已被支付 / 已被其它执行者关闭——都不是错误
            return false;
        }
        walletPaymentMapper.markClosedByOrderNo(order.getOrderNo());
        log.info("[RECHARGE-CLOSE] 超时关单 orderNo={} expireAt={}", order.getOrderNo(), order.getExpireAt());
        return true;
    }
}
