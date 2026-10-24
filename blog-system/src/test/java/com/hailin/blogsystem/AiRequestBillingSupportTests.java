package com.hailin.blogsystem;

import com.hailin.blogsystem.ai.TokenUsageAccumulator;
import com.hailin.blogsystem.ai.billing.AiRequestBillingSupport;
import com.hailin.blogsystem.ai.billing.BillingHandle;
import com.hailin.blogsystem.config.BlogAiProperties;
import com.hailin.blogsystem.service.AiBillingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 计费外壳（{@link AiRequestBillingSupport}）纯单测，不起 Spring 上下文。
 *
 * <p>这里锁的是 B1-B3 三条路径共用的那部分语义。最要紧的一条是
 * **零用量必须走 release**：{@code AiBillingServiceImpl.computeActualCredit} 在
 * totalTokens ≤ 0 时按预扣**全额**结算（那是给「用户拿到了回答、但 provider 没回 usage」用的），
 * 所以零消耗请求若误走 settle，用户会被白收整个硬上限。
 */
class AiRequestBillingSupportTests {

    private static final Long USER_ID = 42L;
    private static final String AGENT = AiRequestBillingSupport.BIZ_AGENT;
    private static final String WORKFLOW = AiRequestBillingSupport.BIZ_WORKFLOW_ACTION;

    private AiBillingService aiBillingService;
    private AiRequestBillingSupport support;

    @BeforeEach
    void setUp() {
        aiBillingService = mock(AiBillingService.class);
        BlogAiProperties properties = new BlogAiProperties();
        properties.getBilling().setMaxReservePerBizType(Map.of(
                AGENT, 8_000L,
                WORKFLOW, 10_000L));

        support = new AiRequestBillingSupport(aiBillingService, properties);
    }

    /** 预扣额度取该 bizType 在配置里的硬上限——不是估算值，也不是 P95 */
    @Test
    void reserveUsesConfiguredCapOfThatBizType() {
        when(aiBillingService.reserve(anyString(), anyString(), anyLong(), anyLong()))
                .thenReturn(new BillingHandle("B-1", USER_ID, 10_000L));

        support.reserve(WORKFLOW, "WF:1", USER_ID);

        verify(aiBillingService).reserve(WORKFLOW, "WF:1", USER_ID, 10_000L);
    }

    /**
     * bizType 没配上限 → 不计费。
     *
     * <p>刻意 fail-open：宁可不收，也不能拿一个拍脑袋的默认值把余额扣穿。
     */
    @Test
    void unconfiguredBizTypeIsNotBilledAtAll() {
        assertThat(support.reserve("SOMETHING_NEW", "X:1", USER_ID)).isNull();
        verifyNoInteractions(aiBillingService);
    }

    /** 有真实用量 → settle，且用量要如实传下去（不能顺手把预扣当成实际用量） */
    @Test
    void closeSettlesWithActualTokens() {
        BillingHandle handle = new BillingHandle("B-2", USER_ID, 8_000L);

        support.close(handle, 1_234L);

        ArgumentCaptor<TokenUsageAccumulator> usage =
                ArgumentCaptor.forClass(TokenUsageAccumulator.class);
        verify(aiBillingService).settle(eq(handle), usage.capture());
        assertThat(usage.getValue().getTotalTokens()).isEqualTo(1_234);
    }

    /** 零总量 → release，绝不 settle */
    @Test
    void zeroTotalIsReleasedNotSettled() {
        BillingHandle handle = new BillingHandle("B-3", USER_ID, 8_000L);

        support.close(handle, 0L);

        verify(aiBillingService).release(handle);
        verify(aiBillingService, never()).settle(any(), any());
    }

    /** 带输入/输出拆分的重载同样是「零消耗 → release」 */
    @Test
    void zeroSplitUsageIsReleasedNotSettled() {
        BillingHandle handle = new BillingHandle("B-4", USER_ID, 10_000L);

        support.close(handle, 0L, 0L);

        verify(aiBillingService).release(handle);
        verify(aiBillingService, never()).settle(any(), any());
    }

    /** 只有输入或只有输出也算有消耗 */
    @Test
    void splitUsageWithEitherSidePositiveSettles() {
        BillingHandle handle = new BillingHandle("B-5", USER_ID, 10_000L);

        support.close(handle, 300L, 0L);

        ArgumentCaptor<TokenUsageAccumulator> usage =
                ArgumentCaptor.forClass(TokenUsageAccumulator.class);
        verify(aiBillingService).settle(eq(handle), usage.capture());
        assertThat(usage.getValue().getTotalTokens()).isEqualTo(300);
    }

    @Test
    void abortReleasesFullReserve() {
        BillingHandle handle = new BillingHandle("B-6", USER_ID, 8_000L);

        support.abort(handle);

        verify(aiBillingService).release(handle);
    }

    /** null 句柄（未计费 / 预扣前就失败）在三个出口都必须空转，不能 NPE */
    @Test
    void nullHandleIsToleratedEverywhere() {
        support.close(null, 100L);
        support.close(null, 100L, 200L);
        support.abort(null);

        verifyNoInteractions(aiBillingService);
    }
}
