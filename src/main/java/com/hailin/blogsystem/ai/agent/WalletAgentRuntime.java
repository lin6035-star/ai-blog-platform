package com.hailin.blogsystem.ai.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hailin.blogsystem.ai.LogValues;
import com.hailin.blogsystem.entity.AiAgentRun;
import com.hailin.blogsystem.entity.dto.AgentStepActionType;
import com.hailin.blogsystem.entity.dto.AiAgentRunStatus;
import com.hailin.blogsystem.entity.dto.PageContextDTO;
import com.hailin.blogsystem.mapper.AiAgentRunMapper;
import com.hailin.blogsystem.mapper.AiAgentStepMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 钱包域 Runtime（第 4 个 Agent 域）：查余额 / 充值。
 *
 * <p><b>它是唯一一个决策器不调 LLM 的域</b>——见 {@link WalletAgentStepDecider}。
 * 骨架部分（run 创建、状态机、SSE、同 session 过期提案清理、token 记账）全部白拿。
 *
 * <p>⚠️ <b>本域禁止「拒绝后让下一轮收尾」这个模式</b>，与三个 LLM 域刻意不同：
 * 基类主循环只有 {@code usedSteps} 一个推进变量，而确定性决策器每一轮的输入完全相同，
 * 「返回 null → 回到循环顶 → 再决策」必然得到同一个提案，<b>是真死循环</b>
 * （SSE 永不 complete，直到准入超时）。学习域之所以没事，是因为它的 null 出口都自己
 * 推进了步数、且靠 LLM 下一轮换招——这两条在本域都不成立。
 * 所以这里 {@code handleExtraTerminalAction} 对 SUGGEST_WRITE <b>永不返回 null</b>，
 * 校验失败一律 {@code markFailed}（run 直接终态结束）。
 */
@Service
@Slf4j
public class WalletAgentRuntime extends AbstractAgentRuntime implements AgentRuntime {

    private static final Set<AgentStepActionType> ALLOWED_ACTIONS = Set.of(
            AgentStepActionType.ASK_USER,
            AgentStepActionType.FINAL_ANSWER,
            AgentStepActionType.SUGGEST_WRITE
    );

    /** 钱包域不发起任何 Workflow */
    private static final Set<String> ALLOWED_SUGGEST_WORKFLOW_TYPES = Set.of();

    private final WalletAgentStepDecider decider;

    /**
     * @param executor 通用域的执行器。<b>本域白名单里只有终态动作，所以它不可达</b>
     *                 ——基类要求传一个非空的 {@code AgentActionExecutor}，而按基类型
     *                 （{@code AgentActionExecutor}）注入会撞三个实现报
     *                 {@code NoUniqueBeanDefinitionException}，所以这里注入通用域的
     *                 marker 接口。不要为一个不可达路径另写一个抛异常的 stub 类。
     */
    public WalletAgentRuntime(WalletAgentStepDecider decider,
                              GeneralAgentActionExecutor executor,
                              AiAgentRunMapper runMapper,
                              AiAgentStepMapper stepMapper,
                              ObjectMapper objectMapper) {
        super(executor, runMapper, stepMapper, objectMapper);
        this.decider = decider;
    }

    @Override
    protected AgentStepDecider decider() {
        return decider;
    }

    @Override
    protected Set<AgentStepActionType> allowedActions() {
        return ALLOWED_ACTIONS;
    }

    @Override
    protected Set<String> allowedSuggestWorkflowTypes() {
        return ALLOWED_SUGGEST_WORKFLOW_TYPES;
    }

    @Override
    protected int maxSteps() {
        return 3;
    }

    /**
     * ⚠️ <b>返回 false 是刻意的，语义上有悖直觉</b>：它确实是个终态动作。
     *
     * <p>正常情况下这个钩子对本域<b>不可达</b>——{@link #handleExtraTerminalAction}
     * 对 SUGGEST_WRITE 永不返回 null，所以基类走不到这里。返回 false 是一道防死循环的
     * 保险：万一将来有人加了一条返回 null 的出口，流程会掉进 executor 抛
     * {@code UnsupportedOperationException} → 落一条 FAILED step + 推进步数 → <b>有界</b>；
     * 返回 true 则会 {@code continue} 回循环顶，撞上确定性决策器的同一个提案 → 死循环。
     */
    @Override
    protected boolean isTerminalExtension(AgentStepDecision decision) {
        return false;
    }

    @Override
    protected AgentRunResult handleExtraTerminalAction(AiAgentRun run, AgentStepDecision decision,
                                                       String goal, List<String> observations,
                                                       AgentStepEmitter emitter, PageContextDTO pageContext) {
        if (decision.actionType() != AgentStepActionType.SUGGEST_WRITE) {
            // 非终态动作不归这里管，交回基类（FINAL_ANSWER / ASK_USER 会被基类的终态分支接走）
            return null;
        }

        Map<String, Object> input = decision.input() == null ? Map.of() : decision.input();
        String actionType = text(input.get("actionType"));
        if (!AgentWriteProposal.TYPE_RECHARGE_WALLET.equals(actionType)) {
            emitter.emit(run.getUsedSteps() + 1, "SUGGEST_WRITE", "FAILED", "不支持的写动作类型", null);
            // C0.2：actionType 是模型给的内部协议值，只进日志（用户可见文案保持稳定）
            log.warn("[AGENT] 写动作提案被拒（actionType 不在白名单）: runId={}, actionType={}",
                    run.getId(), LogValues.safe(actionType));
            return markFailed(run, "Agent 写动作提案无效（不支持的写动作类型）");
        }

        String payYuan = text(input.get("payYuan"));
        if (payYuan == null || payYuan.isBlank()) {
            // 决策器正常情况下不会给空金额，走到这里说明两边脱节了
            emitter.emit(run.getUsedSteps() + 1, "SUGGEST_WRITE", "FAILED", "提案缺少充值金额", null);
            return markFailed(run, "Agent 写动作提案无效（缺少 payYuan）");
        }

        return finishSuggestWrite(run, decision, observations, emitter, payYuan);
    }

    /**
     * 提案落库：{@code WAITING_WRITE_CONFIRM} + {@code context_json.pendingWriteAction}。
     *
     * <p>文案口径与三个 LLM 域一致（「已为你准备好…，确认后执行」）——
     * <b>绝不能写「充值已完成」</b>：这一步只是把卡递到用户面前，钱要等他点确认。
     */
    private AgentRunResult finishSuggestWrite(AiAgentRun run, AgentStepDecision decision,
                                              List<String> observations, AgentStepEmitter emitter,
                                              String payYuan) {
        AgentWriteProposal proposal = new AgentWriteProposal(
                AgentWriteProposal.TYPE_RECHARGE_WALLET,
                null, null, null, false, null, null, null,
                payYuan);

        String finalAnswer = "已为你准备好 " + payYuan + " 元的充值方案，确认后就会到账。";

        emitter.emit(run.getUsedSteps() + 1, "SUGGEST_WRITE", "SUCCESS", "已准备充值方案",
                decision.thoughtSummary());
        recordTerminalStep(run, decision, run.getUsedSteps() + 1, null);
        run.setStatus(AiAgentRunStatus.WAITING_WRITE_CONFIRM.name());
        run.setFinalAnswer(finalAnswer);
        run.setContextJson(toJson(Map.of(
                "observations", clipContext(observations),
                "pendingWriteAction", proposal
        )));
        run.setUpdatedAt(LocalDateTime.now());
        runMapper.updateById(run);

        log.info("Agent Run 写动作提案: runId={}, actionType={}, payYuan={}",
                run.getId(), proposal.actionType(), payYuan);

        return AgentRunResult.of(
                run.getId(),
                AiAgentRunStatus.WAITING_WRITE_CONFIRM,
                finalAnswer,
                run.getUsedSteps(),
                run.getTotalTokens(),
                null,
                proposal
        );
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    @Override
    protected String emptyAnswerFallback() {
        return "这次没有完成充值，你可以再说一次要充多少。";
    }

    @Override
    protected String emptyAskUserFallback() {
        return "你想充多少额度？";
    }

    @Override
    protected String defaultWorkflowSuggestionReason(String workflowType) {
        return "建议进入「" + workflowType + "」流程。";
    }

    @Override
    protected String suggestWorkflowRejectHint() {
        return "系统提示：钱包域不发起 Workflow，本次建议已被后端拒绝。";
    }

    @Override
    protected String emptySummaryFallback() {
        return "这次没有完成充值，你可以再说一次要充多少。";
    }

    @Override
    protected String summaryHeader() {
        return "根据已有信息，整理如下：\n";
    }
}
