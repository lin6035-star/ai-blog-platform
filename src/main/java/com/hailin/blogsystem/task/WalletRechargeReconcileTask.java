package com.hailin.blogsystem.task;

import com.hailin.blogsystem.entity.WalletPayment;
import com.hailin.blogsystem.entity.WalletRechargeOrder;
import com.hailin.blogsystem.mapper.WalletPaymentMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 充值订单对账（设计稿 §9）。
 *
 * <p><b>它和钱包流水对账（{@code WalletReconcileTask}）的分工</b>：
 * 那边查「账本自身是否自洽」（余额快照连不连续），这边查「本地记录和渠道事实是否对得上」。
 * 接真实支付后，渠道侧换成对账单文件；虚拟支付的渠道事实就是本地的支付单，
 * 所以这里能抓的是「绕过回调的写入」和「回调推进了一半」。
 *
 * <p><b>刻意不自动修复</b>：钱的问题只有人知道哪个是对的。
 * 补一笔流水、改一个状态，都是拿猜测覆盖事实——沿用 {@code WalletReconcileTask} 的原则。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WalletRechargeReconcileTask {

    /** 单轮最多报多少条：同一段代码出错会一次污染一批，全打出来只会淹掉真正有用的那行 */
    private static final int MAX_REPORT = 50;

    private final WalletPaymentMapper walletPaymentMapper;

    @Scheduled(fixedDelay = 600_000L)
    public void reconcile() {
        reportSuccessWithoutPaidOrder();
        reportPaidWithoutSuccessPayment();
        reportAmountMismatch();
    }

    /** 渠道已收款、本地订单却没入账——最贵的一类不一致 */
    private void reportSuccessWithoutPaidOrder() {
        List<WalletPayment> rows = walletPaymentMapper.selectSuccessWithoutPaidOrder(MAX_REPORT);
        if (rows.isEmpty()) {
            return;
        }
        log.error("[RECHARGE-RECONCILE] 支付单已成功但订单未入账（掉单）{} 条{}：{}",
                rows.size(),
                rows.size() >= MAX_REPORT ? "（已达单轮上限，可能还有更多）" : "",
                rows.stream().map(p -> p.getPaymentNo() + "→" + p.getOrderNo()).toList());
    }

    /** 订单已 PAID、却没有任何成功支付单——只可能来自绕过回调的写入 */
    private void reportPaidWithoutSuccessPayment() {
        List<WalletRechargeOrder> rows = walletPaymentMapper.selectPaidWithoutSuccessPayment(MAX_REPORT);
        if (rows.isEmpty()) {
            return;
        }
        log.error("[RECHARGE-RECONCILE] 订单已支付但无成功支付单{} 条：{}",
                rows.size() >= MAX_REPORT ? "（已达单轮上限，可能还有更多）" : "",
                rows.stream().map(WalletRechargeOrder::getOrderNo).toList());
    }

    /** 支付单成功金额与订单应付金额不一致（串单 / 数据被改） */
    private void reportAmountMismatch() {
        List<WalletPayment> rows = walletPaymentMapper.selectAmountMismatch(MAX_REPORT);
        if (rows.isEmpty()) {
            return;
        }
        log.error("[RECHARGE-RECONCILE] 支付单金额与订单金额不一致 {} 条：{}",
                rows.size(),
                rows.stream().map(p -> p.getPaymentNo() + "(" + p.getPayAmount() + ")").toList());
    }
}
