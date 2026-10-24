package com.hailin.blogsystem.task;

import com.hailin.blogsystem.config.BlogWalletProperties;
import com.hailin.blogsystem.entity.WalletPayment;
import com.hailin.blogsystem.mapper.WalletPaymentMapper;
import com.hailin.blogsystem.service.WalletRechargeNotifyService;
import com.hailin.blogsystem.wallet.ChannelPaymentStatus;
import com.hailin.blogsystem.wallet.PaymentCallback;
import com.hailin.blogsystem.wallet.PaymentChannel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 主动查单补单（设计稿 §6.5）。
 *
 * <p><b>它补的是什么</b>：渠道回调可能丢失 / 延迟 / 被防火墙挡掉。真实支付里
 * 「回调没到」不等于「没付钱」——必须有一个兜底去渠道问。
 *
 * <p>虚拟渠道的查单目前不会返回 SUCCESS（渠道状态由回调驱动），
 * 但这条路径真实执行；接真实渠道时只换 {@link PaymentChannel#query} 实现。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WalletPaymentSyncTask {

    private static final int BATCH = 100;

    private final WalletPaymentMapper walletPaymentMapper;
    private final PaymentChannel paymentChannel;
    private final WalletRechargeNotifyService walletRechargeNotifyService;
    private final BlogWalletProperties walletProperties;

    @Scheduled(fixedDelay = 120_000L)
    public void syncStalePayments() {
        long staleSeconds = Math.max(1L, walletProperties.getPayment().getQueryPendingAfterSeconds());
        LocalDateTime before = LocalDateTime.now().minusSeconds(staleSeconds);
        List<WalletPayment> stale = walletPaymentMapper.selectStaleCreated(before, BATCH);
        if (stale.isEmpty()) {
            return;
        }

        int recovered = 0;
        for (WalletPayment payment : stale) {
            try {
                if (paymentChannel.query(payment) != ChannelPaymentStatus.SUCCESS) {
                    continue;
                }
                PaymentCallback callback = new PaymentCallback(
                        payment.getPaymentNo(),
                        payment.getOrderNo(),
                        payment.getChannelTradeNo(),
                        payment.getPayAmount(),
                        System.currentTimeMillis() / 1000,
                        "{\"source\":\"CHANNEL_QUERY\"}");
                if (walletRechargeNotifyService.applyChannelSuccess(payment, callback)) {
                    recovered++;
                }
            } catch (Exception e) {
                log.warn("[PAYMENT-SYNC] 单笔补单失败已跳过，可重跑：paymentNo={}", payment.getPaymentNo(), e);
            }
        }
        if (recovered > 0) {
            log.warn("[PAYMENT-SYNC] 主动查单补单成功 {} 笔（说明回调曾丢失），候选={}",
                    recovered, stale.size());
        }
    }
}
