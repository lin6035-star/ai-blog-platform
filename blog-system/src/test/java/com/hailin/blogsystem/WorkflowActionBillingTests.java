package com.hailin.blogsystem;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hailin.blogsystem.ai.task.AiOrchestrationTaskAdmission;
import com.hailin.blogsystem.ai.workflow.AiWorkflowStepEmitter;
import com.hailin.blogsystem.ai.workflow.WorkflowRunManager;
import com.hailin.blogsystem.entity.AiBillingOrder;
import com.hailin.blogsystem.entity.AiChatEventType;
import com.hailin.blogsystem.entity.AiWorkflowRun;
import com.hailin.blogsystem.entity.vo.AiChatEventVO;
import com.hailin.blogsystem.entity.vo.AiWorkflowRunVO;
import com.hailin.blogsystem.mapper.AiBillingOrderMapper;
import com.hailin.blogsystem.service.AiWorkflowRunService;
import com.hailin.blogsystem.service.AiWorkflowStreamService;
import com.hailin.blogsystem.service.WalletService;
import com.hailin.blogsystem.utils.UserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.TestPropertySource;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Workflow 动作路径（approve / reject / retry）的 AI 计费。
 *
 * <p>审计：{@code docs/计费覆盖审计.md}。这条路径原先**完全不计费**——
 * 每点一次「同意」都是一次真实 LLM 生成，且 retry 可以反复触发，用户余额纹丝不动。
 *
 * <p>这里不跑真 LLM：用量来源是 {@code ai_workflow_runs} 上 input/output 的**执行前后差量**，
 * 所以只要 mock 掉 handler（改 run 的 token）就能精确锁住三个出口：
 * 有消耗按实际扣、零消耗全额退、读不到 run 也不能让预扣悬挂。
 *
 * <p>每个用例用**独立 userId**：H2 是同一个内存库、上下文跨用例复用，
 * 共用 userId 会让余额断言互相污染（踩过一次）。
 */
@SpringBootTest
@TestPropertySource(properties = {
        "blog.ai.billing.max-reserve-per-biz-type.WORKFLOW_ACTION=10000"
})
class WorkflowActionBillingTests {

    private static final Long RUN_ID = 9_882_002L;
    private static final String BIZ_WORKFLOW_ACTION = "WORKFLOW_ACTION";
    /** 预扣硬上限，来自上面的 max-reserve-per-biz-type.WORKFLOW_ACTION */
    private static final long RESERVE_CAP = 10_000L;

    @Autowired
    private AiWorkflowStreamService streamService;

    @Autowired
    private WalletService walletService;

    @Autowired
    private AiBillingOrderMapper orderMapper;

    @MockBean
    private AiOrchestrationTaskAdmission admission;

    @MockBean
    private AiWorkflowRunService workflowRunService;

    @MockBean
    private WorkflowRunManager workflowRunManager;

    private Long userId;
    private AiWorkflowRun run;

    @BeforeEach
    void setUp() {
        userId = 9_890_000_000_000L + Math.abs(System.nanoTime() % 1_000_000_000L);
        UserContext.set(userId);

        run = new AiWorkflowRun();
        run.setId(RUN_ID);
        run.setStatus("WAITING_PLAN_CONFIRM");
        run.setInputTokens(0);
        run.setOutputTokens(0);
        run.setTotalTokens(0);

        when(workflowRunManager.getOwnedRun(RUN_ID, userId)).thenAnswer(invocation -> run);
        when(workflowRunService.listStepLogs(RUN_ID)).thenReturn(List.of());

        // 准入：precheck 放行（void 默认空实现），submit 就地执行——测试不需要真线程池，
        // 就地执行还能让 UserContext 与主线程保持一致。
        doAnswer(invocation -> {
            invocation.getArgument(1, Runnable.class).run();
            return null;
        }).when(admission).submit(any(), any());
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    /**
     * 主路径：预扣用的是配置里的**硬上限**（10000），结算按真实用量退差额。
     * 1000 token → 10 credit（credit-per-1k-tokens=10）。
     */
    @Test
    void approveChargesActualUsageAndRefundsTheDifference() {
        recharge(20_000);
        when(workflowRunService.approve(eq(RUN_ID), anyString(), any(AiWorkflowStepEmitter.class)))
                .thenAnswer(invocation -> {
                    run.setInputTokens(300);
                    run.setOutputTokens(700);
                    run.setTotalTokens(1_000);
                    return completedVo();
                });

        drain(streamService.approve(RUN_ID, "key-charge"));

        AiBillingOrder order = onlyWorkflowOrder();
        assertThat(order.getReservedCredit()).isEqualTo(RESERVE_CAP);
        assertThat(order.getStatus()).isEqualTo("SETTLED");
        assertThat(order.getActualCredit()).isEqualTo(10L);
        assertThat(order.getTotalTokens()).isEqualTo(1_000);
        assertThat(walletService.getWallet(userId).getBalance()).isEqualTo(19_990L);
    }

    /**
     * 最要紧的一条：**零消耗必须走 release，不能走 settle(0)**。
     *
     * <p>{@code AiBillingServiceImpl.computeActualCredit} 在 totalTokens ≤ 0 时按预扣**全额**结算
     * （那是为「用户确实拿到了回答、但 provider 没回 usage」准备的），
     * 所以这里如果误用 settle，用户会白付 10000 credit。
     * 触发场景很常见：幂等重放命中缓存、首步就失败。
     */
    @Test
    void zeroUsageIsReleasedNotSettled() {
        recharge(20_000);
        // handler 正常返回但一个 token 都没产生 = 幂等缓存命中 / 没跑到 LLM
        when(workflowRunService.approve(eq(RUN_ID), anyString(), any(AiWorkflowStepEmitter.class)))
                .thenReturn(completedVo());

        drain(streamService.approve(RUN_ID, "key-zero"));

        AiBillingOrder order = onlyWorkflowOrder();
        assertThat(order.getStatus()).isEqualTo("RELEASED");
        assertThat(order.getActualCredit()).isNull();
        assertThat(walletService.getWallet(userId).getBalance()).isEqualTo(20_000L);
    }

    /** 动作失败但确实烧了 token → 按实际扣（token 是真花出去的，全退等于白送） */
    @Test
    void failedActionStillChargesBurnedTokens() {
        recharge(20_000);
        when(workflowRunService.approve(eq(RUN_ID), anyString(), any(AiWorkflowStepEmitter.class)))
                .thenAnswer(invocation -> {
                    run.setInputTokens(200);
                    run.setOutputTokens(300);
                    run.setTotalTokens(500);
                    throw new IllegalStateException("handler 执行失败");
                });

        List<AiChatEventVO> events = drain(streamService.approve(RUN_ID, "key-fail"));

        assertThat(events)
                .as("失败要如实下发错误事件，不能被这次改造吞掉")
                .anyMatch(e -> AiChatEventType.WORKFLOW_ERROR.getValue().equals(e.getEventType()));
        AiBillingOrder order = onlyWorkflowOrder();
        assertThat(order.getStatus()).isEqualTo("SETTLED");
        assertThat(order.getActualCredit()).isEqualTo(5L);
        assertThat(walletService.getWallet(userId).getBalance()).isEqualTo(19_995L);
    }

    /**
     * 余额 ≤ 0 时在**任何 LLM 调用之前**就被拒，且不留 RESERVED 孤儿单
     * （孤儿单会一直占着预扣，只能等 30 分钟超时兜底释放）。
     */
    @Test
    void insufficientBalanceRejectsBeforeCallingHandler() {
        recharge(100);
        // 第一次调用把余额扣穿：预扣 10000 → 用量超上限被按 10000 封顶结算 → 余额 -9900
        when(workflowRunService.approve(eq(RUN_ID), anyString(), any(AiWorkflowStepEmitter.class)))
                .thenAnswer(invocation -> {
                    run.setInputTokens(1_000_000);
                    run.setOutputTokens(1_000_000);
                    run.setTotalTokens(2_000_000);
                    return completedVo();
                });
        drain(streamService.approve(RUN_ID, "key-eat"));
        assertThat(walletService.getWallet(userId).getBalance())
                .as("预扣门槛是 balance > 0，所以一笔正常预扣本来就会把余额扣成负数")
                .isNegative();

        List<AiChatEventVO> events = drain(streamService.approve(RUN_ID, "key-poor"));

        assertThat(events)
                .anyMatch(e -> AiChatEventType.WORKFLOW_ERROR.getValue().equals(e.getEventType()));
        verify(workflowRunService, times(1))
                .approve(eq(RUN_ID), anyString(), any(AiWorkflowStepEmitter.class));
        assertThat(workflowOrders())
                .as("第二次连单都不该开出来（预扣事务整体回滚）")
                .hasSize(1);
    }

    // ---------- helpers ----------

    private List<AiChatEventVO> drain(Flux<AiChatEventVO> flux) {
        List<AiChatEventVO> events = flux.collectList().block();
        return events == null ? List.of() : events;
    }

    private AiWorkflowRunVO completedVo() {
        AiWorkflowRunVO vo = new AiWorkflowRunVO();
        vo.setId(String.valueOf(RUN_ID));
        vo.setStatus("COMPLETED");
        return vo;
    }

    private void recharge(long amount) {
        // 幂等键必须唯一：wallet_transaction 上有 (user_id, idempotency_key) 唯一约束
        String tag = "WF-BILL-" + System.nanoTime();
        walletService.increase(userId, amount, "RECHARGE", "RECHARGE_ORDER",
                tag, "RECHARGE:" + tag, null);
    }

    private List<AiBillingOrder> workflowOrders() {
        return orderMapper.selectList(new LambdaQueryWrapper<AiBillingOrder>()
                .eq(AiBillingOrder::getUserId, userId)
                .eq(AiBillingOrder::getBizType, BIZ_WORKFLOW_ACTION));
    }

    private AiBillingOrder onlyWorkflowOrder() {
        List<AiBillingOrder> orders = workflowOrders();
        assertThat(orders)
                .as("这次 Workflow 动作应当只开出一张计费单")
                .hasSize(1);
        return orders.get(0);
    }
}
