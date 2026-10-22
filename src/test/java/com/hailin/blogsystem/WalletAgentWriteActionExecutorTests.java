package com.hailin.blogsystem;

import com.hailin.blogsystem.ai.agent.AgentWriteProposal;
import com.hailin.blogsystem.ai.agent.WalletAgentWriteActionExecutor;
import com.hailin.blogsystem.entity.AiAgentRun;
import com.hailin.blogsystem.entity.vo.PaymentInitiateVO;
import com.hailin.blogsystem.entity.vo.RechargeOrderVO;
import com.hailin.blogsystem.entity.vo.WalletVO;
import com.hailin.blogsystem.exception.BusinessException;
import com.hailin.blogsystem.service.WalletRechargeService;
import com.hailin.blogsystem.service.WalletService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 钱包域写动作执行器。
 *
 * <p><b>最要紧的断言是「只创建订单 + 发起支付，不代替用户支付」</b>：
 * {@code createCustomOrder} 落 PENDING 订单、{@code initiatePayment} 返回 payToken，
 * 支付动作交给前端支付窗口（用户点确认才走渠道回调入账）。
 *
 * <p>⚠️ 这里如果调了 {@code mockPay}，就等于 AI 替用户确认付款——接真实渠道后
 * 根本不存在这种能力，所以用 {@code verify(..., never())} 锁死。
 */
class WalletAgentWriteActionExecutorTests {

    private WalletRechargeService walletRechargeService;
    private WalletService walletService;
    private WalletAgentWriteActionExecutor executor;

    @BeforeEach
    void setUp() {
        walletRechargeService = mock(WalletRechargeService.class);
        walletService = mock(WalletService.class);
        executor = new WalletAgentWriteActionExecutor(walletRechargeService, walletService);
    }

    private static AgentWriteProposal proposal(String payYuan) {
        return new AgentWriteProposal(AgentWriteProposal.TYPE_RECHARGE_WALLET,
                null, null, null, false, null, null, null, payYuan);
    }

    private static AiAgentRun run() {
        AiAgentRun run = new AiAgentRun();
        run.setId(1L);
        return run;
    }

    private static RechargeOrderVO order(String orderNo, long creditAmount) {
        RechargeOrderVO vo = new RechargeOrderVO();
        vo.setOrderNo(orderNo);
        vo.setCreditAmount(creditAmount);
        vo.setPayAmount(creditAmount / 10);
        vo.setStatus("PENDING");
        return vo;
    }

    private static PaymentInitiateVO initiate(String payToken) {
        PaymentInitiateVO vo = new PaymentInitiateVO();
        vo.setPaymentNo("P-" + payToken);
        vo.setPayToken(payToken);
        vo.setOrderStatus("PENDING");
        return vo;
    }

    private void stubCreateAndInitiate(String orderNo, long creditAmount) {
        when(walletRechargeService.createCustomOrder(any())).thenReturn(order(orderNo, creditAmount));
        when(walletRechargeService.initiatePayment(orderNo)).thenReturn(initiate("TOKEN-" + orderNo));
    }

    private static WalletVO wallet(long balance) {
        WalletVO vo = new WalletVO();
        vo.setBalance(balance);
        vo.setPendingReserveCount(0L);
        return vo;
    }

    @Test
    void supportsOnlyRechargeWallet() {
        assertThat(executor.supports(AgentWriteProposal.TYPE_RECHARGE_WALLET)).isTrue();
        assertThat(executor.supports(AgentWriteProposal.TYPE_UPDATE_TASK_DONE)).isFalse();
    }

    /** 只创建订单 + 发起支付，把 payToken 交给前端支付窗口——**不能替用户支付** */
    @Test
    void createsOrderAndInitiatesPaymentOnly() {
        when(walletRechargeService.createCustomOrder(new BigDecimal("10")))
                .thenReturn(order("W-TEST-1", 10_000L));
        when(walletRechargeService.initiatePayment("W-TEST-1")).thenReturn(initiate("TOKEN-W-TEST-1"));
        when(walletService.getWallet(anyLong())).thenReturn(wallet(0L));

        var result = executor.execute(run(), proposal("10"), 100L);

        verify(walletRechargeService).createCustomOrder(new BigDecimal("10"));
        verify(walletRechargeService).initiatePayment("W-TEST-1");
        // ⚠️ 这一条是核心：AI 不确认付款，钱必须由用户在支付窗口里点确认
        verify(walletRechargeService, never()).mockPay(any(), any());
        assertThat(result.message()).contains("已创建充值订单");
        assertThat(result.auditPayload())
                .containsEntry("orderNo", "W-TEST-1")
                .containsEntry("paymentNo", "P-TOKEN-W-TEST-1")
                .containsEntry("payToken", "TOKEN-W-TEST-1")
                .containsEntry("payYuan", "10")
                .containsEntry("creditAmount", 10_000L)
                .containsEntry("orderStatus", "PENDING")
                .containsEntry("beforeBalance", 0L);
    }

    /** 到账额度用**实际下单结果**，不是提案时刻的估算——兑换比例可能已经被改过 */
    @Test
    void reportsCreditFromActualOrderNotFromEstimate() {
        stubCreateAndInitiate("W-TEST-2", 25_000L);
        when(walletService.getWallet(anyLong())).thenReturn(wallet(0L));

        var result = executor.execute(run(), proposal("10"), 100L);

        // 额度不再出现在回复文案里（未到账），由支付窗口按订单快照展示
        assertThat(result.auditPayload()).containsEntry("creditAmount", 25_000L);
    }

    @Test
    void rejectsMissingAmount() {
        assertThatThrownBy(() -> executor.execute(run(), proposal(null), 100L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("缺少充值金额");
        assertThatThrownBy(() -> executor.execute(run(), proposal("  "), 100L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("缺少充值金额");
        verify(walletRechargeService, never()).createCustomOrder(any());
    }

    @Test
    void rejectsUnparsableAmount() {
        assertThatThrownBy(() -> executor.execute(run(), proposal("十块"), 100L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("金额无效");
        verify(walletRechargeService, never()).createCustomOrder(any());
    }

    /**
     * 越界金额由服务层的权威闸门拒绝，执行器**不自己判**——
     * 阈值只有一处（{@code requireWholeYuan}），在这里复制一份迟早漂。
     */
    @Test
    void letsServiceLayerRejectOutOfRangeAmount() {
        when(walletRechargeService.createCustomOrder(new BigDecimal("0")))
                .thenThrow(new IllegalArgumentException("充值金额需在 1 ~ 1000 元之间"));

        assertThatThrownBy(() -> executor.execute(run(), proposal("0"), 100L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1 ~ 1000");
    }
}
