package com.hailin.blogsystem.ai.billing;

import com.hailin.blogsystem.ai.TokenUsageAccumulator;
import com.hailin.blogsystem.config.BlogAiProperties;
import com.hailin.blogsystem.service.AiBillingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 「一次用户可见的 AI 请求」的计费外壳（Workflow / Agent 等路径复用）。
 *
 * <p>普通聊天早就在 {@code AiMessageServiceImpl.streamNormalChatFlow} 里做了
 * 预扣 → 结算，但那是**最终落点方法**：分发器在它之前 return 的分支
 * （Workflow / Agent / Task 编排）一条都走不到。审计见 {@code docs/计费覆盖审计.md}。
 * 本类把同一套语义抽出来，避免每条路径各写一遍预扣 / 释放。
 *
 * <p><b>预扣额度取该 bizType 在 {@code max-reserve-per-biz-type} 里的硬上限</b>——
 * 这正是那个配置项存在的理由（预扣必须是可执行硬上限，不能取 P95）。
 * 未配置的 bizType 直接不计费并告警：宁可不收，也不能拿一个拍脑袋的默认值把余额扣穿。
 *
 * <p><b>{@link #close} 的零用量分支必须是 release，不能是 settle(0)</b>：
 * {@code AiBillingServiceImpl.computeActualCredit} 在 totalTokens ≤ 0 时按**预扣全额**结算
 * （那是为「用户确实拿到了回答、但 provider 没回 usage」准备的）。
 * 所以「幂等重放命中缓存」「第一步就失败」这类零消耗请求必须显式走全额退回。
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class AiRequestBillingSupport {

    /** Agent Runtime 路径（含 Task 编排里的分析步）。 */
    public static final String BIZ_AGENT = "AGENT";

    /** Workflow 的用户动作：启动 / approve / reject / retry。 */
    public static final String BIZ_WORKFLOW_ACTION = "WORKFLOW_ACTION";

    private final AiBillingService aiBillingService;
    private final BlogAiProperties blogAiProperties;

    /**
     * 预扣。余额 ≤ 0 时抛 {@code InsufficientBalanceException}，由调用方决定怎么呈现。
     *
     * @return 句柄；bizType 未配上限（或计费总开关关闭）时返回 {@code null}，
     *         后续 {@link #close} / {@link #abort} 收到 null 即空转
     */
    public BillingHandle reserve(String bizType, String bizId, Long userId) {
        long cap = capOf(bizType);
        if (cap <= 0) {
            log.warn("[BILLING] bizType={} 未配置 max-reserve-per-biz-type，本次不计费：bizId={}",
                    bizType, bizId);
            return null;
        }
        return aiBillingService.reserve(bizType, bizId, userId, cap);
    }

    /**
     * 结算本次请求的实际用量；零用量直接全额退回。
     *
     * <p>为什么要拆成两个参数而不是收一个累加器：Workflow 路径的用量来源是
     * {@code ai_workflow_runs} 上的 input/output 差量，本来就拿不到 {@code Usage} 对象。
     */
    public void close(BillingHandle handle, long promptTokens, long completionTokens) {
        if (handle == null) {
            return;
        }
        if (promptTokens <= 0 && completionTokens <= 0) {
            // 零消耗：本次请求没真正跑 LLM（幂等重放命中缓存 / 首步即失败）
            aiBillingService.release(handle);
            return;
        }
        TokenUsageAccumulator usage = new TokenUsageAccumulator();
        usage.add((int) Math.max(0, promptTokens), (int) Math.max(0, completionTokens));
        aiBillingService.settle(handle, usage);
    }

    /** 无条件全额退回（预扣了但无法判断实际用量时用）。 */
    public void abort(BillingHandle handle) {
        if (handle != null) {
            aiBillingService.release(handle);
        }
    }

    /** 回填关联资源（纯观测：assistantMessageId / agentRunId，方便从计费单反查到业务对象）。 */
    public void bindResource(BillingHandle handle, String resourceId) {
        aiBillingService.bindResource(handle, resourceId);
    }

    /**
     * 结算重载：**只知道总量**时用（Agent run 与编排子树只对外给 total）。
     *
     * <p>prompt 记 0、completion 记总量。{@code prompt_tokens} / {@code completion_tokens}
     * 在计费单里只是观测字段，真正的计费依据是 totalTokens，所以这样记不影响算钱。
     */
    public void close(BillingHandle handle, long totalTokens) {
        if (handle == null) {
            return;
        }
        if (totalTokens <= 0) {
            aiBillingService.release(handle);
            return;
        }
        TokenUsageAccumulator usage = new TokenUsageAccumulator();
        usage.add(0, (int) totalTokens);
        aiBillingService.settle(handle, usage);
    }

    private long capOf(String bizType) {
        Long cap = blogAiProperties.getBilling().getMaxReservePerBizType().get(bizType);
        return cap == null ? 0L : cap;
    }
}
