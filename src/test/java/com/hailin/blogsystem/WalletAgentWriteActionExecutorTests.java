package com.hailin.blogsystem;

import com.hailin.blogsystem.ai.agent.AgentWriteProposal;
import com.hailin.blogsystem.ai.agent.WalletAgentWriteActionExecutor;
import com.hailin.blogsystem.entity.AiAgentRun;
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
 * <p>最要紧的一条断言是 {@code verify(pay)}：{@code createCustomOrder} 只落一张
 * {@code PENDING} 订单、**不加余额**，真正到账靠 {@code pay}。
 * 只做前者的话，用户点了「确认充值」却看到钱没到——这是最容易犯的错。
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
        return vo;
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

    /** 下单**和**支付都要做——只下单的话钱不会到账 */
    @Test
    void createsOrderAndPaysIt() {
        when(walletRechargeService.createCustomOrder(new BigDecimal("10")))
                .thenReturn(order("W-TEST-1", 10_000L));
        when(walletService.getWallet(anyLong())).thenReturn(wallet(0L), wallet(10_000L));

        var result = executor.execute(run(), proposal("10"), 100L);

        verify(walletRechargeService).createCustomOrder(new BigDecimal("10"));
        verify(walletRechargeService).pay("W-TEST-1");
        assertThat(result.message()).contains("10,000");
        assertThat(result.auditPayload())
                .containsEntry("orderNo", "W-TEST-1")
                .containsEntry("payYuan", "10")
                .containsEntry("creditAmount", 10_000L)
                .containsEntry("beforeBalance", 0L)
                .containsEntry("afterBalance", 10_000L);
    }

    /** 到账额度用**实际下单结果**，不是提案时刻的估算——兑换比例可能已经被改过 */
    @Test
    void reportsCreditFromActualOrderNotFromEstimate() {
        when(walletRechargeService.createCustomOrder(any())).thenReturn(order("W-TEST-2", 25_000L));
        when(walletService.getWallet(anyLong())).thenReturn(wallet(0L), wallet(25_000L));

        var result = executor.execute(run(), proposal("10"), 100L);

        assertThat(result.message()).contains("25,000");
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
