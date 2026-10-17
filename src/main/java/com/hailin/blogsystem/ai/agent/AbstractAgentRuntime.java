package com.hailin.blogsystem.ai.agent;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hailin.blogsystem.ai.AiStageTimer;
import com.hailin.blogsystem.ai.LogValues;
import com.hailin.blogsystem.ai.TokenUsageAccumulator;
import com.hailin.blogsystem.entity.AiAgentRun;
import com.hailin.blogsystem.entity.AiAgentStep;
import com.hailin.blogsystem.entity.dto.AiAgentRunStatus;
import com.hailin.blogsystem.entity.dto.AiAgentStepStatus;
import com.hailin.blogsystem.entity.dto.AgentStepActionType;
import com.hailin.blogsystem.entity.dto.PageContextDTO;
import com.hailin.blogsystem.mapper.AiAgentRunMapper;
import com.hailin.blogsystem.mapper.AiAgentStepMapper;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 领域 Agent 公共循环骨架（V2.5 抽取）。
 *
 * 循环形状（原 LearningAgentRuntime V1 只读语义，领域无关）：
 * <pre>
 * create AgentRun
 * while usedSteps < maxSteps:
 *   decider.decide(...)                       // 决策下一步（LLM / mock）
 *   validate action against whitelist         // 后端校验白名单
 *   FINAL_ANSWER  -> 保存回答，COMPLETED
 *   ASK_USER      -> 保存问题，WAITING_USER
 *   SUGGEST_WORKFLOW -> 零观察裁判 -> 建议（确认门控，不直接启动）
 *   execute readonly action                   // executor 执行，observation 落 step + 进上下文
 * maxSteps 到顶 -> 从已有 observations 汇总回答
 * </pre>
 *
 * 领域差异全部走钩子（decider / 白名单 / 文案 / SUGGEST_WRITE 扩展终态），
 * 学习域与文章域各自薄壳继承，不复制循环。
 *
 * 边界（与 V1 一致）：
 * - 非法动作 / 空决策 -> FAILED + 安全文案
 * - 动作执行失败 -> step 记 FAILED，追加失败 observation，循环继续
 * - 单条 observation ≤ 1500 字符，累计上下文 ≤ 6000 字符
 */
@Slf4j
public abstract class AbstractAgentRuntime {

    protected static final int MAX_OBSERVATION_CHARS = 1500;
    protected static final int MAX_CONTEXT_CHARS = 6000;
    protected static final int DEFAULT_MAX_STEPS = 5;

    protected final AgentActionExecutor executor;
    protected final AiAgentRunMapper runMapper;
    protected final AiAgentStepMapper stepMapper;
    protected final ObjectMapper objectMapper;

    protected AbstractAgentRuntime(
            AgentActionExecutor executor,
            AiAgentRunMapper runMapper,
            AiAgentStepMapper stepMapper,
            ObjectMapper objectMapper
    ) {
        this.executor = executor;
        this.runMapper = runMapper;
        this.stepMapper = stepMapper;
        this.objectMapper = objectMapper;
    }

    // ==================== 领域钩子 ====================

    protected abstract AgentStepDecider decider();

    protected abstract Set<AgentStepActionType> allowedActions();

    protected abstract Set<String> allowedSuggestWorkflowTypes();

    /** FINAL_ANSWER emitter 成功文案（中性，刷新补拉共用 AgentStepLabelSupport 同源）。 */
    protected String finalAnswerSuccessMessage() {
        return "已生成最终回答";
    }

    /** FINAL_ANSWER 缺 answer 时兜底正文。 */
    protected abstract String emptyAnswerFallback();

    /** ASK_USER 缺 question 时兜底正文。 */
    protected abstract String emptyAskUserFallback();

    /** SUGGEST_WORKFLOW 缺 reason 时兜底原因。 */
    protected abstract String defaultWorkflowSuggestionReason(String workflowType);

    /** SUGGEST_WORKFLOW 零观察拒绝时追加进上下文的系统提示（含本领域只读动作列表）。 */
    protected abstract String suggestWorkflowRejectHint();

    /** maxSteps 到顶且零观察时的兜底正文。 */
    protected abstract String emptySummaryFallback();

    /** maxSteps 到顶拼接 observation 的抬头。 */
    protected abstract String summaryHeader();

    /** SUGGEST_WORKFLOW 建议携带的 articleId（仅文章域非空；学习域默认 null）。 */
    protected String resolveSuggestionArticleId(PageContextDTO pageContext, AiAgentRun run) {
        return null;
    }

    /** 决策目标（学习域原样；文章域注入当前文章 + 会话锚候选线索）。 */
    protected String effectiveGoal(String goal, Long userId, Long sessionId, PageContextDTO pageContext) {
        return goal;
    }

    /**
     * V3.8：每步决策后解析本 run 的定位目标（文章域用；默认不解析）。
     * 决议结果存 run.targetArticleId（瞬态，不落库）。解析失败（目标候选缺失/无效）时置 null，
     * 由动作执行层按缺失目标处理（FAILED step + observation，循环继续 LLM 收尾，不猜）。
     */
    protected void resolveStepTarget(AiAgentRun run, AgentStepDecision decision, PageContextDTO pageContext) {
    }

    /**
     * V3.8：动作执行前对决策做后端预处理（文章域把决议目标并入 QUERY_ARTICLE 的 input）。
     * 终态动作（FINAL_ANSWER / ASK_USER / SUGGEST_*）不经此钩子。
     *
     * <p><b>2026-10-16 起默认实现不再是「原样返回」</b>：给 QUERY_MEMORY 并入
     * {@code userGoal}（run 的原始目标原话），供执行器选检索词。
     * 实测同一问题下原话 cosine=0.6241，而 LLM 改写出的检索词只有 0.5607——
     * 改写丢掉的信息比口语噪声更伤召回（原话 0.6241 &gt; 拼接 0.6023 &gt; 改写 0.5607）。
     *
     * <p>为什么塞进 input 而不是加 execute 参数：这是本钩子的既有用法（后端注入，非 LLM 输出）。
     * goal 在整个 run 内恒定，所以 {@code rejectDuplicatedQuery} 的 input_json 精确比对不受影响。
     * {@code queryParamLabel} 只读 focus / question / keyword，userGoal 不会进观察文本。
     *
     * <p>⚠️ 文章域重写了本方法，必须继续调 super，否则文章域 QUERY_MEMORY 拿不到原话。
     */
    protected AgentStepDecision prepareStepDecision(AiAgentRun run, AgentStepDecision decision) {
        if (decision.actionType() != AgentStepActionType.QUERY_MEMORY) {
            return decision;
        }
        String goal = run == null ? null : run.getRuntimeGoal();
        if (goal == null || goal.isBlank()) {
            return decision;
        }
        Map<String, Object> merged = new HashMap<>(
                decision.input() == null ? Map.of() : decision.input());
        merged.put("userGoal", goal);
        return decision.withInput(merged);
    }

    /**
     * V3.8：动作执行成功回调（文章域写会话锚；默认空）。执行失败不回调。
     */
    protected void onStepSucceeded(AiAgentRun run, AgentStepDecision decision, String observation) {
    }

    /**
     * P2.2：领域结构化运行状态工厂（per-run）。
     *
     * <p>默认返回 {@code null} —— 学习 / 通用 / 钱包域继续用 observation 重放，行为与改造前一致。
     * 文章域覆写它返回 {@code ArticleAgentState}：决策上下文改由结构化槽位组装（§7.6），
     * 已完成动作 / 证据 / 缺失证据从 {@link AgentActionResult#payload()} 归约，不再重放整条文本流。
     *
     * <p><b>必须每次 new</b>：返回的对象会被整个 run 复用，放在单例字段上会串号。
     */
    protected AgentRunState newRunState(String goal, int maxSteps) {
        return null;
    }

    /**
     * P2.3 修订：把运行期反馈同时写进结构化状态——**观察是给审计看的，状态是给模型看的**。
     *
     * <p>只写 observations 的老做法在结构化路径下会让模型瞎掉：它只看 {@code decisionContext()}，
     * 于是"刚才为什么被拒、下一步该补什么"全部丢失，容易重复同样的动作直到步数耗尽。
     */
    private void noteFeedback(AgentRunState runState, String message) {
        noteFeedback(runState, message, null);
    }

    private void noteFeedback(
            AgentRunState runState,
            String message,
            AgentRunState.FeedbackClearKey clearKey
    ) {
        if (runState != null && message != null && !message.isBlank()) {
            runState.noteFeedback(message, clearKey);
        }
    }

    /** 领域扩展终态新增反馈的生命周期；默认反馈保留到本 run 结束。 */
    protected AgentRunState.FeedbackClearKey extraTerminalFeedbackClearKey(
            AgentStepDecision decision,
            int observationsBefore
    ) {
        return null;
    }

    /**
     * P2.3 修订：领域钩子在 observations 尾部追加的拒绝提示，同样要进结构化状态。
     *
     * <p>「零观察就提案写动作被拒」「同名改标题被拒」「文章已隐藏」这些反馈原先**只写 observations**，
     * 而模型只看 {@code decisionContext()} —— 它会反复提同一个注定被拒的提案，直到步数耗尽。
     *
     * <p>用**索引差**同步而不是给 {@code handleExtraTerminalAction} 加参数：三个域都覆写了它，
     * 改签名要动三处、还漏一个就再犯一次；而它们追加反馈的方式本来就统一是 {@code observations.add}。
     * 这些内容都是"系统提示：……被拒绝"型反馈（终态动作不产生真正的事实观察），按反馈处理是安全的。
     */
    private void syncFeedback(
            AgentRunState runState,
            AgentStepDecision decision,
            List<String> observations,
            int fromIndex
    ) {
        if (runState == null || fromIndex >= observations.size()) {
            return;
        }
        AgentRunState.FeedbackClearKey clearKey =
                extraTerminalFeedbackClearKey(decision, fromIndex);
        for (int i = fromIndex; i < observations.size(); i++) {
            noteFeedback(runState, observations.get(i), clearKey);
        }
    }

    /**
     * P2.2：决策上下文——有结构化状态时用它，否则沿用 observation 重放。
     *
     * <p>两种情况都保持"每步只带必要信息"的既有约束（默认路径的裁剪口径未变）。
     */
    private String decisionContext(AgentRunState runState, List<String> observations) {
        return runState != null ? runState.decisionContext() : clipContext(observations);
    }

    /**
     * 动作执行失败是否属于「终局失败」（V2.5）。
     *
     * 终局失败 = 继续循环也无法改变结果（如文章归属校验失败），
     * 失败 step 落库后直接结束 run（COMPLETED + 后端直接生成友好文案，不再走 LLM）。
     * 默认 false：临时故障（如 ES 挂了）保持循环继续，由下一轮决策收尾。
     */
    protected boolean isTerminalFailure(Throwable e) {
        return false;
    }

    // ==================== V3.11 证据收敛门钩子 ====================

    /**
     * V3.11：FINAL_ANSWER 前的证据收敛门（领域钩子）。
     *
     * 返回 null = 不验证（默认，学习/通用域原路径）；返回 Verdict：
     * - NEED_MORE：拦截该 FINAL_ANSWER（落 FAILED step + 提示 observation），循环继续补查
     * - ASK_USER：转问（构造新 ASK_USER decision，question 来自验证器）
     * - SUFFICIENT：放行走原收尾
     */
    protected Verdict verifyBeforeFinalization(
            AiAgentRun run,
            AgentStepDecision decision,
            List<String> observations,
            List<AgentStepActionType> successfulActions,
            AgentRunState runState
    ) {
        return null;
    }

    /** 仅需要独立 Generator 的领域覆写为 true；默认域继续使用 Decider 的 answer/message。 */
    protected boolean usesGeneratedFinalAnswer() {
        return false;
    }

    /** 独立最终正文生成钩子，仅在 {@link #usesGeneratedFinalAnswer()} 为 true 时调用。 */
    protected GeneratedAnswer generateFinalAnswer(
            AiAgentRun run,
            AgentStepDecision decision,
            List<String> observations,
            AgentRunState runState,
            AgentStepEmitter emitter
    ) {
        return new GeneratedAnswer(
                extractFinalAnswerText(decision), new TokenUsageAccumulator(), false);
    }

    // ==================== V3.13 Plan Preview 域钩子 ====================

    /**
     * V3.13：Plan Preview 域开关。**默认 false**，仅文章域为 true。
     *
     * 共享骨架会触及三域（`AgentStepDecision.plan` 字段 / 决策器解析 / 采集落库发事件），
     * 没有显式开关就意味着学习域和通用域被动吃到 plan 逻辑，且无法从代码上断言
     * 「这两个域没变」。解析层例外（无域语义），prompt 规则与采集链路都在开关之内。
     */
    protected boolean supportsPlanPreview() {
        return false;
    }

    /**
     * V3.11：maxSteps 到顶前的保守收尾门（领域钩子，默认 false = 原 summarize 收尾）。
     * 触发条件由领域实现（如：目标文章从没成功读过 / 存在未被满足的验证拒绝），
     * 返回 true 时走 {@link #completeWithConservativeShutdown}，不给 summarize 硬答的机会。
     */
    protected boolean conservativeShutdownRequired(
            AiAgentRun run,
            List<AgentStepActionType> successfulActions,
            boolean hasUnresolvedVerifierRejection
    ) {
        return false;
    }

    /** V3.11：保守收尾文案（默认空兜底，领域 override 给用户可读解释）。 */
    protected String conservativeShutdownMessage() {
        return emptySummaryFallback();
    }

    /** V3.11：NEED_MORE 拦截后追加进上下文的系统提示（领域可 override 补动作指引）。 */
    protected String verifierRejectHint(Verdict verdict) {
        return "系统提示：你的回答依据不足（缺 " + verdict.missingEvidenceLabel()
                + "）：" + (verdict.reason() == null || verdict.reason().isBlank()
                ? "证据不足以支撑该回答" : verdict.reason())
                + "。请先补充查询再回答，不要编造。";
    }

    /** 决策循环步数上限（学习/文章域默认 5；通用域收紧到 3 控延迟）。 */
    protected int maxSteps() {
        return DEFAULT_MAX_STEPS;
    }

    /**
     * 领域扩展终态（如学习域 SUGGEST_WRITE）。
     * 返回非 null 表示已作为终态处理；默认 null 继续走通用终态。
     */
    /**
     * 扩展终态动作豁免普通执行：领域钩子拒绝（返回 null）后循环跳过 execute，
     * 直接进入下一轮决策（拒绝路径已自行推进 step 计数与观察）。
     */
    protected boolean isTerminalExtension(AgentStepDecision decision) {
        return false;
    }

    protected AgentRunResult handleExtraTerminalAction(
            AiAgentRun run,
            AgentStepDecision decision,
            String goal,
            List<String> observations,
            AgentStepEmitter emitter,
            PageContextDTO pageContext,
            TokenUsageAccumulator stepUsage
    ) {
        return null;
    }

    /**
     * V3.12：FINAL_ANSWER 终态结论钩子（默认空实现，只有需要结论继承的域覆写）。
     *
     * 只挂 completeWithAnswer 一处——那是 FINAL_ANSWER 的唯一出口。**不能挂 finish()**：
     * 它同时服务保守收尾与 maxSteps 汇总，那两种不是「结论」。
     * SUGGEST_WORKFLOW 的结论走 confirm 时刻写（AgentRunSuggestionService），不在骨架。
     *
     * **调用时机**：Agent Run 已落库（finish 之后）才调——先写锚后落库会在落库失败时
     * 留下孤儿锚（结论锚存在但 run 没成功完成）。只有 run 成功落库才允许产生结论锚。
     *
     * fail-open：实现方必须自行兜异常（写锚失败不能影响用户看到回答）。
     */
    protected void onAnswerConcluded(
            AiAgentRun run,
            String finalAnswer,
            List<AgentStepActionType> successfulActions
    ) {
    }

    // ==================== 公共入口 ====================

    /**
     * 终局失败内部信号（V2.5）：执行器抛出的终局异常由 executeActionAndRecordStep
     * 转换后抛出，run() 捕获后直接收尾（不继续循环、不走 LLM）。
     */
    protected static class AgentRunTerminalException extends RuntimeException {
        private final int stepNo;

        public AgentRunTerminalException(int stepNo, String message) {
            super(message);
            this.stepNo = stepNo;
        }

        public int getStepNo() {
            return stepNo;
        }
    }

    public AgentRunResult run(Long userId, Long sessionId, String goal) {
        return run(userId, sessionId, goal, null, AgentStepEmitter.noop());
    }

    public AgentRunResult run(
            Long userId,
            Long sessionId,
            String goal,
            AgentStepEmitter emitter
    ) {
        return run(userId, sessionId, goal, null, emitter);
    }

    public AgentRunResult run(
            Long userId,
            Long sessionId,
            String goal,
            PageContextDTO pageContext,
            AgentStepEmitter emitter
    ) {
        return runWithPolicy(userId, sessionId, goal, pageContext, emitter, defaultPolicy());
    }

    /**
     * 默认运行策略：等价于本域既有的 decider / 白名单 / 允许等待语义。
     * 走它的四个既有域（学习 / 文章 / 通用 / 钱包）行为零变化。
     */
    protected AgentRunPolicy defaultPolicy() {
        return new AgentRunPolicy(decider(), allowedActions(), true);
    }

    /**
     * 带策略的执行体。窄模式（analysis-only）由域入口传策略进来。
     *
     * 骨架里所有与「用哪个 decider / 允许哪些动作 / 能否进等待态」有关的判断都读 policy，
     * 而不是读域实现的抽象方法——否则同一次 Run 里会出现「白名单窄了、prompt 没窄」这类半吊子状态。
     */
    protected final AgentRunResult runWithPolicy(
            Long userId,
            Long sessionId,
            String goal,
            PageContextDTO pageContext,
            AgentStepEmitter emitter,
            AgentRunPolicy policy
    ) {
        return runWithPolicy(userId, sessionId, goal, pageContext, emitter, policy, null);
    }

    /**
     * 带父 run 的执行体（第四步）。
     *
     * @param parentRunId 外层 Task run 的 ID；非 null 表示本次是**子 run**——
     *                    不清同 session 的待确认卡（见 {@link #createRun}），
     *                    并在日志里额外带 taskRunId，排查时能同时定位外层任务与内部执行
     */
    protected final AgentRunResult runWithPolicy(
            Long userId,
            Long sessionId,
            String goal,
            PageContextDTO pageContext,
            AgentStepEmitter emitter,
            AgentRunPolicy policy,
            Long parentRunId
    ) {
        String effectiveGoal = effectiveGoal(goal, userId, sessionId, pageContext);
        AiAgentRun run = createRun(userId, sessionId, effectiveGoal, parentRunId);
        log.info("Agent Run 创建: runId={}, taskRunId={}, userId={}, goal={}",
                run.getId(), parentRunId, userId, LogValues.safe(effectiveGoal, 100));

        // V4⑥ 可观测性：run 期间的所有日志带上 agentRunId，与 traceId 配合做「日志 ↔ DB」互查
        MDC.put("agentRunId", String.valueOf(run.getId()));
        if (parentRunId != null) {
            // 子 run 额外带外层任务 id：只看子 run 的日志无法定位"这是哪次用户请求的执行"
            MDC.put("taskRunId", String.valueOf(parentRunId));
        }

        List<String> observations = new ArrayList<>();
        // P2.2：领域结构化状态（per-run）。为 null = 走 observation 重放——学习 / 通用 / 钱包域如此
        AgentRunState runState = newRunState(effectiveGoal, run.getMaxSteps());
        List<AgentStepActionType> successfulActions = new ArrayList<>();   // V3.11：结构化事实（只读动作成功列表）
        List<Integer> successfulActionSteps = new ArrayList<>();           // V3.11：成功动作步号（到顶判据用）
        // V3.11：最后一次验证拒绝的步号（run 局部）。到顶保守收尾判据 =
        // 「最后一次验证拒绝之后没有再成功补查」——拦后补到新材料就不该被保守一刀切
        // （2026-09-10 手测修正：原「拦过即保守」把「补查成功但没步数答」的场景也保守了）
        Integer lastVerifierRejectionStep = null;
        try {

            while (run.getUsedSteps() < run.getMaxSteps()) {
                int nextStepNo = run.getUsedSteps() + 1;

                // 本步决策的用量单独收集：既累到 run 级，也落到 step 行（谁花的算谁的）
                TokenUsageAccumulator stepUsage = new TokenUsageAccumulator();
                AiStageTimer stepTimer = AiStageTimer.start();
                AgentStepDecision decision;
                try {
                    decision = policy.decider().decide(
                            effectiveGoal,
                            decisionContext(runState, observations),
                            nextStepNo,
                            run.getMaxSteps(),
                            stepUsage
                    );
                    log.info("[PERF-AI] stage=agent_decider runId={} stepNo={} durationMs={} outcome={}",
                            run.getId(), nextStepNo, stepTimer.elapsedMillis(),
                            decision == null || decision.actionType() == null ? "empty" : "success");
                } catch (RuntimeException e) {
                    log.info("[PERF-AI] stage=agent_decider runId={} stepNo={} durationMs={} outcome=error",
                            run.getId(), nextStepNo, stepTimer.elapsedMillis());
                    throw e;
                }
                AgentTokenRecorder.accumulate(run, stepUsage);

                // 白名单校验：null / 非法动作直接 FAILED
                if (decision == null || decision.actionType() == null
                        || !policy.allowedActions().contains(decision.actionType())) {
                    boolean emptyDecision = decision == null || decision.actionType() == null;
                    String rawAction = emptyDecision ? "" : decision.actionType().name();
                    String detail = emptyDecision ? "空决策" : "非法动作";
                    log.warn("[AGENT] 决策不在当前运行策略白名单: runId={}, actionType={}",
                            run.getId(), LogValues.safe(rawAction));
                    emitter.emit(nextStepNo, "DECISION", "FAILED", "决策无效：" + detail, null);
                    return markFailed(run, "Agent 决策无效（" + detail + "）");
                }

                // V3.13 Plan Preview：首步采集计划（仅域开关开启；全程 fail-open）
                collectPlanIfApplicable(run, decision, nextStepNo, emitter);

                // V3.8：解析本 run 定位目标（在扩展终态与动作执行前，SUGGEST_WRITE 提案端消费 run 决议目标）
                resolveStepTarget(run, decision, pageContext);

                // 领域扩展终态（学习域 SUGGEST_WRITE 在此处理）
                int observedBefore = observations.size();
                AgentRunResult extra = handleExtraTerminalAction(
                        run, decision, effectiveGoal, observations, emitter, pageContext, stepUsage);
                syncFeedback(runState, decision, observations, observedBefore);
                if (extra != null) {
                    return extra;
                }
                // 扩展终态动作被领域拒绝（零观察 / 重复预检）→ 跳过本轮普通执行，
                // 观察已带拒绝原因推进，循环由 LLM 下一轮决策收尾
                if (isTerminalExtension(decision)) {
                    continue;
                }

                // 终态：FINAL_ANSWER
                if (decision.actionType() == AgentStepActionType.FINAL_ANSWER) {
                    // 同一缺失类型被连续拒绝到上限、**且已经用掉最后一次机会**时才放行（2026-10-11）：
                    // 还有步数时继续让证据门工作（既有语义：拦后补查成功 → 到顶不保守）；
                    // 但最后一步再拦就等于彻底放弃回答——实测「点赞 + 分析」6 步全被拦、
                    // 18300 token 全浪费，用户拿到的只是"信息不足，先不硬答"。
                    //
                    // **必须排在下面那条"无进展收尾"之前**：那条的结局同样是"没有回答"。
                    boolean lastChance = run.getMaxSteps() != null && nextStepNo >= run.getMaxSteps();
                    boolean verifierExhausted = lastChance
                            && runState != null && runState.verifierRejectionExhausted();

                    // 上次 Verifier 已明确缺某类证据，而此后对应证据版本没有变化：
                    // 再调一次 Verifier 只会重复同一结论，随后继续空转。
                    // 默认状态实现恒返回 true，所以只对启用结构化证据版本的文章域生效。
                    if (!verifierExhausted && runState != null && !runState.hasEvidenceProgressSinceRejection()) {
                        String reason = "上次拒绝后没有新增证据";
                        emitter.emit(nextStepNo, "FINAL_ANSWER", AiAgentStepStatus.SKIPPED.name(),
                                "没有新增证据，停止重复回答", null);
                        recordSkippedStep(run, decision, nextStepNo, reason, stepUsage,
                                stepTimer.elapsedMillis());
                        run.setCurrentStep(nextStepNo);
                        run.setUsedSteps(nextStepNo);
                        run.setContextJson(toJson(clipContext(observations)));
                        run.setUpdatedAt(LocalDateTime.now());
                        runMapper.updateById(run);
                        return completeWithConservativeShutdown(run, observations);
                    }

                    // V3.11 证据收敛门：领域钩子判定「证据是否足以支撑该回答草案」。
                    // NEED_MORE 拦截回循环补查；ASK_USER 转问；SUFFICIENT/null（无验证器）放行。
                    //
                    // 埋点（2026-10-04）：这一步此前既无耗时也无判定记录，于是"被拒后重试"的
                    // 成本一直算不出来。实测 decider 一次 FINAL_ANSWER 要 ~30s（生成完整草案），
                    // 而拒绝发生在草案生成**之后**——真正贵的是被丢掉的那份草案，不是判定本身。
                    // outcome 记 verdict 类型，用 NEED_MORE 占比就能直接读出空转比例。
                    Verdict verdict;
                    if (verifierExhausted) {
                        // verdict = null 与原"该域没有验证器"同义：证据门未参与判定 → 走下面的放行分支。
                        // 留一条可检索日志，便于从"被丢弃的草案数"反查证据门是否长期空转。
                        log.warn("[AGENT] 证据门连续同类型拒绝达上限，本轮放行草案避免空转: runId={} stepNo={}",
                                run.getId(), nextStepNo);
                        verdict = null;
                    } else {
                        AiStageTimer verifierTimer = AiStageTimer.start();
                        try {
                            verdict = verifyBeforeFinalization(
                                    run, decision, observations, successfulActions, runState);
                            log.info("[PERF-AI] stage=agent_verifier runId={} stepNo={} durationMs={} outcome={}",
                                    run.getId(), nextStepNo, verifierTimer.elapsedMillis(),
                                    verdict == null ? "none" : verdict.type());
                        } catch (RuntimeException e) {
                            log.info("[PERF-AI] stage=agent_verifier runId={} stepNo={} durationMs={} outcome=error",
                                    run.getId(), nextStepNo, verifierTimer.elapsedMillis());
                            throw e;
                        }
                    }
                    if (verdict != null && verdict.type() == Verdict.VerdictType.NEED_MORE) {
                        lastVerifierRejectionStep = nextStepNo;
                        if (runState != null) {
                            runState.noteVerifierRejection(
                                    Verdict.MissingEvidence.from(verdict.missingEvidence()));
                        }
                        String rejectReason = "回答证据不足（缺 " + verdict.missingEvidenceLabel()
                                + "）：" + (verdict.reason() == null || verdict.reason().isBlank()
                                ? "证据不足以支撑该回答" : verdict.reason());
                        emitter.emit(nextStepNo, "FINAL_ANSWER", "FAILED",
                                "回答被拦截：证据不足", null);
                        recordRejectedStep(run, decision, nextStepNo, rejectReason, stepUsage,
                                stepTimer.elapsedMillis());
                        String verifierHint = verifierRejectHint(verdict);
                        observations.add(verifierHint);
                        // 带上缺失类型：补到该类证据时要能把它清掉，否则旧反馈会自我强化
                        noteFeedback(runState, verifierHint,
                                AgentRunState.FeedbackClearKey.from(
                                        Verdict.MissingEvidence.from(verdict.missingEvidence())));
                        run.setCurrentStep(nextStepNo);
                        run.setUsedSteps(nextStepNo);
                        run.setContextJson(toJson(clipContext(observations)));
                        run.setUpdatedAt(LocalDateTime.now());
                        runMapper.updateById(run);
                        continue;
                    }
                    if (verdict != null && verdict.type() == Verdict.VerdictType.ASK_USER) {
                        if (!policy.allowWaitingUser()) {
                            // analysis-only：本模式不允许转问——复合请求必须保持单请求闭环（§4.7）。
                            // 信息不足就保守收尾：如实说明缺什么，不伪装成功。
                            emitter.emit(nextStepNo, "FINAL_ANSWER", "SUCCESS",
                                    "分析信息不足，保守收尾", decision.thoughtSummary());
                            return completeWithConservativeShutdown(run, observations);
                        }
                        // 转问：构造新 ASK_USER decision（question 来自验证器，verifier 侧已保证非空），
                        // 只落一条 ASK_USER 终态 step，不保留伪造的 FINAL_ANSWER step
                        AgentStepDecision askDecision = new AgentStepDecision(
                                AgentStepActionType.ASK_USER,
                                Map.of("question", verdict.question() == null ? "" : verdict.question()),
                                null);
                        emitter.emit(nextStepNo, "ASK_USER", "SUCCESS",
                                "需要向你确认一个问题", null);
                        return markWaitingUser(run, askDecision, observations, stepUsage,
                                stepTimer.elapsedMillis());
                    }
                    if (usesGeneratedFinalAnswer()) {
                        return completeWithGeneratedAnswer(
                                run, decision, observations, successfulActions, runState,
                                emitter, stepUsage, stepTimer);
                    }
                    emitter.emit(nextStepNo, "FINAL_ANSWER", "SUCCESS",
                            finalAnswerSuccessMessage(), decision.thoughtSummary());
                    return completeWithAnswer(run, decision, observations, successfulActions, stepUsage,
                            stepTimer.elapsedMillis());
                }

                // 终态：ASK_USER（问题快照留 run）
                if (decision.actionType() == AgentStepActionType.ASK_USER) {
                    emitter.emit(nextStepNo, "ASK_USER", "SUCCESS",
                            "需要向你确认一个问题", decision.thoughtSummary());
                    return markWaitingUser(run, decision, observations, stepUsage,
                            stepTimer.elapsedMillis());
                }

                // 终态：SUGGEST_WORKFLOW
                // 裁判规则：至少 1 条观察才允许建议（防"没查就拍脑袋"）。
                // 零观察建议被拒：落 FAILED step + 追加拒绝提示，循环继续（maxSteps 有界保护）。
                if (decision.actionType() == AgentStepActionType.SUGGEST_WORKFLOW) {
                    if (observations.isEmpty()) {
                        String rejectReason = "首轮零观察建议被拒绝：必须先执行至少一个只读查询";
                        emitter.emit(nextStepNo, "SUGGEST_WORKFLOW", "FAILED",
                                "建议被拒绝：需先完成一次查询", null);
                        recordRejectedStep(run, decision, nextStepNo, rejectReason, stepUsage,
                                stepTimer.elapsedMillis());
                        String suggestHint = suggestWorkflowRejectHint();
                        observations.add(suggestHint);
                        noteFeedback(runState, suggestHint, AgentRunState.FeedbackClearKey.QUERY_REQUIRED);
                        run.setCurrentStep(nextStepNo);
                        run.setUsedSteps(nextStepNo);
                        run.setContextJson(toJson(clipContext(observations)));
                        run.setUpdatedAt(LocalDateTime.now());
                        runMapper.updateById(run);
                        continue;
                    }
                    return suggestWorkflow(run, decision, effectiveGoal, observations, emitter, pageContext,
                            stepUsage, stepTimer.elapsedMillis());
                }

                // V3.8：后端预处理（决议目标并入 QUERY_ARTICLE input）。
                // 提到事件之前，让重复检查比对的是**实际落库的 input**（含后端注入的决议目标）
                AgentStepDecision prepared = prepareStepDecision(run, decision);

                // V4 规则级重复拦截：同动作 + 同参数的查询再来一次不会有新结果
                if (rejectDuplicatedQuery(run, prepared, nextStepNo, observations, emitter, stepUsage,
                        runState, stepTimer.elapsedMillis())) {
                    run.setCurrentStep(nextStepNo);
                    run.setUsedSteps(nextStepNo);
                    run.setContextJson(toJson(clipContext(observations)));
                    run.setUpdatedAt(LocalDateTime.now());
                    runMapper.updateById(run);
                    continue;
                }

                // 执行只读动作（执行前后推步骤事件，前端实时渲染思考过程）
                // V3.10：RUNNING/SUCCESS 携带同一句 thoughtSummary（行文本跨状态稳定，D4），
                // FAILED 不携带——失败时优先展示失败文案，不让动机句覆盖失败原因
                emitter.emit(nextStepNo, decision.actionType().name(), "RUNNING",
                        "正在" + actionLabel(decision.actionType()) + "...", decision.thoughtSummary());
                AgentActionResult actionResult = executeActionAndRecordStep(
                        run, prepared, userId, nextStepNo, emitter, pageContext,
                        successfulActions, successfulActionSteps, stepUsage, stepTimer
                );
                String observation = actionResult.summary();
                observations.add(observation);
                if (runState != null) {
                    // 结构化事实独立于展示文本：观察加了来源标注、被裁剪，payload 不受影响
                    runState.reduce(prepared, actionResult);
                }

                run.setCurrentStep(nextStepNo);
                run.setUsedSteps(nextStepNo);
                run.setContextJson(toJson(clipContext(observations)));
                run.setUpdatedAt(LocalDateTime.now());
                runMapper.updateById(run);
            }

            // V3.11 到顶路径保守收尾门：领域判定（如目标文章从没读到 / 验证拒绝未被满足）
            // 「未被满足」= 最后一次验证拒绝之后没有再成功补查（2026-09-10 手测修正：
            // 拦后补到新材料的不保守——材料可能已够，交给 summarize 基于新证据收尾）
            Integer lastSuccessfulActionStep = successfulActionSteps.isEmpty()
                    ? null
                    : successfulActionSteps.get(successfulActionSteps.size() - 1);
            boolean hasUnresolvedVerifierRejection = lastVerifierRejectionStep != null
                    && (lastSuccessfulActionStep == null
                    || lastSuccessfulActionStep < lastVerifierRejectionStep);
            if (conservativeShutdownRequired(run, successfulActions, hasUnresolvedVerifierRejection)) {
                return completeWithConservativeShutdown(run, observations);
            }
            // maxSteps 到顶：从已有 observations 汇总回答
            return completeWithSummary(run, effectiveGoal, observations, policy, runState);
        } catch (AgentRunTerminalException e) {
            // 终局失败：失败 step 已落库，这里直接收尾（COMPLETED + 友好文案），不再走 LLM
            log.info("Agent Run 终局失败收尾: runId={}, reason={}", run.getId(), e.getMessage());
            return completeWithTerminalFailure(run, observations, e);
        } catch (AgentAnswerStreamException e) {
            // 已向外部交付部分正文后，必须让 SSE 适配层终止失败流；不能降级成成功回答。
            throw e;
        } catch (Exception e) {
            log.error("Agent Run 执行异常: runId={}", run.getId(), e);
            return markFailed(run, "Agent 执行异常，请稍后重试");
        } finally {
            // token 兜底落库：上面 8 个出口各靠自己的 updateById 顺带写入，
            // 这里再单列补一次，防某个出口走的是不带全字段的单列 UPDATE 导致 token 丢失。
            // 与已有 updateById 重复写同值是幂等的
            persistTokens(run);
            // 只移除本键——不能用 MDC.clear()，它会连 Spring 放的 traceId 一起清掉
            MDC.remove("agentRunId");
            MDC.remove("taskRunId");
        }
    }

    /**
     * token 兜底落库：只更新 token 三列（与 collectPlanIfApplicable 的单列更新同一模式）。
     * 失败只记日志不抛——统计是副产品，坏了不能影响用户拿到回答。
     */
    private void persistTokens(AiAgentRun run) {
        if (run == null || run.getId() == null || run.getTotalTokens() == null) {
            return;
        }
        try {
            runMapper.update(null, new UpdateWrapper<AiAgentRun>()
                    .eq("id", run.getId())
                    .set("input_tokens", run.getInputTokens() == null ? 0 : run.getInputTokens())
                    .set("output_tokens", run.getOutputTokens() == null ? 0 : run.getOutputTokens())
                    .set("total_tokens", run.getTotalTokens()));
        } catch (Exception e) {
            log.warn("[PERF-AGENT] token 落库失败 runId={} totalTokens={}",
                    run.getId(), run.getTotalTokens(), e);
        }
    }

    /**
     * 终局失败收尾：状态 COMPLETED + finalAnswer 用终局失败原因（用户可读），
     * 前端正常展示 AI 文案，不再追加决策步骤。
     */
    private AgentRunResult completeWithTerminalFailure(
            AiAgentRun run,
            List<String> observations,
            AgentRunTerminalException e
    ) {
        String finalAnswer = (e.getMessage() == null || e.getMessage().isBlank())
                ? emptyAnswerFallback()
                : e.getMessage();
        // 失败 step 已落库，这里把进度同步到失败步（step 表数量 = usedSteps 一致）
        run.setCurrentStep(e.getStepNo());
        run.setUsedSteps(e.getStepNo());
        run.setStatus(AiAgentRunStatus.COMPLETED.name());
        run.setFinalAnswer(finalAnswer);
        run.setContextJson(toJson(clipContext(observations)));
        run.setUpdatedAt(LocalDateTime.now());
        runMapper.updateById(run);
        log.info("Agent Run 结束（终局失败）: runId={}, status=COMPLETED, usedSteps={}",
                run.getId(), run.getUsedSteps());
        return AgentRunResult.of(
                run.getId(),
                AiAgentRunStatus.COMPLETED,
                finalAnswer,
                run.getUsedSteps(),
                run.getTotalTokens(),
                AgentCompletionKind.TERMINAL_FAILURE
        );
    }

    // ==================== 通用内部逻辑 ====================

    /**
     * 动作中文名（思考面板展示用，与历史补拉文案一致）。
     */
    private String actionLabel(AgentStepActionType type) {
        return AgentStepLabelSupport.actionLabel(type == null ? null : type.name());
    }

    private String workflowLabel(String workflowType) {
        return AgentStepLabelSupport.workflowLabel(workflowType);
    }

    /**
     * V3.13 Plan Preview：首步采集 / 校验 / 落库 / 发事件。
     *
     * 时序写死：白名单校验通过 → plan 校验 → **单列更新落库（按影响行数判定）** → `emitPlan` → 首个步骤事件。
     * - **先落库再发事件**（与 V3.12 孤儿锚同一原则：事件不可先于持久化）——
     *   落库影响行数 ≠ 1 视为未落库，只记日志不发事件，防「实时有计划、库里没有」分叉
     * - `AGENT_PLAN` 必须在首个 `AGENT_STEP` 事件之前发，否则前端视觉顺序会闪
     * - 落库走**单列更新**，不复用 `updateById`——否则计划写失败会与 run 状态写失败
     *   混成同一个故障，fail-open 形同虚设
     *
     * 全程 fail-open：计划是展示增强，任何失败只丢计划，不影响决策与执行。
     */
    private void collectPlanIfApplicable(
            AiAgentRun run,
            AgentStepDecision decision,
            int stepNo,
            AgentStepEmitter emitter
    ) {
        if (!supportsPlanPreview() || stepNo != 1) {
            return;
        }
        // 首步即终态/需澄清动作时没有实际执行基线，计划无法用于本阶段观测，且可能在澄清前误导用户
        if (isNonExecutableFirstStep(decision.actionType())) {
            return;
        }
        try {
            List<String> plan = AgentPlanValidator.validate(decision.plan());
            if (plan == null || plan.isEmpty()) {
                return;
            }
            String planJson = toJson(plan);
            int updated = runMapper.update(null, new UpdateWrapper<AiAgentRun>()
                    .eq("id", run.getId())
                    .set("plan_json", planJson)
                    .set("updated_at", LocalDateTime.now()));
            if (updated != 1) {
                // 落库未命中（run 行不存在等）→ 只记日志不发事件，防「实时有计划、库里没有」分叉
                log.warn("Plan Preview 落库未命中 run 行，跳过事件: runId={}", run.getId());
                return;
            }
            run.setPlanJson(planJson);
            emitter.emitPlan(run.getId(), plan);
        } catch (Exception e) {
            log.warn("Plan Preview 采集失败（不影响 Agent 流程）: runId={}", run.getId(), e);
        }
    }

    /** 首步即无执行基线（终态 / 需澄清）→ 不采集计划。 */
    private boolean isNonExecutableFirstStep(AgentStepActionType actionType) {
        return actionType == AgentStepActionType.FINAL_ANSWER
                || actionType == AgentStepActionType.ASK_USER
                || actionType == AgentStepActionType.SUGGEST_WORKFLOW
                || actionType == AgentStepActionType.SUGGEST_WRITE;
    }

    private AiAgentRun createRun(Long userId, Long sessionId, String goal) {
        return createRun(userId, sessionId, goal, null);
    }

    /**
     * 创建 run（第四步加父子参数）。
     *
     * <p><b>子 run 不清理同 session 的待确认卡</b>：{@code cancelStalePendingSuggestions} 的语义是
     * 「用户又发起了一轮新判断，旧的建议 / 提案自然过期」。而 {@code parentRunId != null} 表示这是
     * **外层任务内部的执行步骤**，不是新一轮用户交互——照走会让一次内部分析把用户已有的
     * Workflow / 写动作确认卡取消掉（确认接口随后只能报「状态不允许」）。
     *
     * @param parentRunId 外层 Task run 的 ID；null = 根 run（用户发起的那次任务）
     */
    private AiAgentRun createRun(Long userId, Long sessionId, String goal, Long parentRunId) {
        if (parentRunId == null) {
            // 新一轮 Agent 判断开始，同 session 旧的待确认建议/提案自然过期。
            // 子 run 不是"新一轮"——见方法注释。
            cancelStalePendingSuggestions(sessionId);
        }

        AiAgentRun run = new AiAgentRun();
        run.setUserId(userId);
        run.setSessionId(sessionId);
        run.setGoal(truncate(goal, 500));
        run.setRuntimeGoal(goal);
        run.setStatus(AiAgentRunStatus.RUNNING.name());
        run.setCurrentStep(0);
        run.setMaxSteps(maxSteps());
        run.setUsedSteps(0);
        run.setParentRunId(parentRunId);
        LocalDateTime now = LocalDateTime.now();
        run.setCreatedAt(now);
        run.setUpdatedAt(now);
        runMapper.insert(run);
        return run;
    }

    /**
     * 执行动作并落 step。
     *
     * 成功：step SUCCESS + observation 返回给循环，动作加入 successfulActions（V3.11 结构化事实，
     * 供证据收敛门判「某类动作是否成功执行过」，不依赖观察文本格式）。
     * 失败：step FAILED + 失败 observation（限长），循环继续，由下一轮决策收尾。
     */
    private AgentActionResult executeActionAndRecordStep(
            AiAgentRun run,
            AgentStepDecision decision,
            Long userId,
            int stepNo,
            AgentStepEmitter emitter,
            PageContextDTO pageContext,
            List<AgentStepActionType> successfulActions,
            List<Integer> successfulActionSteps,
            TokenUsageAccumulator stepUsage,
            AiStageTimer stepTimer
    ) {
        AiStageTimer actionTimer = AiStageTimer.start();
        AiAgentStep step = new AiAgentStep();
        step.setAgentRunId(run.getId());
        step.setStepNo(stepNo);
        step.setActionType(decision.actionType().name());
        step.setThoughtSummary(decision.thoughtSummary());
        step.setInputJson(toJson(decision.input()));
        step.setStatus(AiAgentStepStatus.RUNNING.name());
        step.setCreatedAt(LocalDateTime.now());
        applyStepTokens(step, stepUsage);
        stepMapper.insert(step);

        try {
            AgentActionResult actionResult = executor.execute(decision, userId, pageContext);
            String observation = actionResult.summary();
            step.setOutputJson(toJson(Map.of("summary", observation)));
            step.setStatus(AiAgentStepStatus.SUCCESS.name());
            step.setDurationMs(stepTimer.elapsedMillis());
            stepMapper.updateById(step);
            log.info("[PERF-AI] stage=agent_action runId={} stepNo={} action={} executorDurationMs={} totalDurationMs={} outcome=success",
                    run.getId(), stepNo, decision.actionType(), actionTimer.elapsedMillis(), step.getDurationMs());
            log.info("Agent Step 成功: runId={}, stepNo={}, action={}", run.getId(), stepNo, decision.actionType());
            emitter.emit(stepNo, decision.actionType().name(), "SUCCESS",
                    "已完成" + actionLabel(decision.actionType()), decision.thoughtSummary());
            // V3.8：执行成功领域回调（文章域写会话锚）
            onStepSucceeded(run, decision, observation);
            // V3.11：成功动作进结构化列表（供证据收敛门判定；步号供到顶保守收尾判据）
            successfulActions.add(decision.actionType());
            successfulActionSteps.add(stepNo);
            // payload 必须原样带出去：它是 Reducer 的输入，不能是加过来源标注的展示文本
            return actionResult.withSummary(tagObservation(decision, clipObservation(observation)));
        } catch (Exception e) {
            log.warn("Agent Step 执行失败: runId={}, stepNo={}, action={}",
                    run.getId(), stepNo, decision.actionType(), e);
            step.setStatus(AiAgentStepStatus.FAILED.name());
            step.setDurationMs(stepTimer.elapsedMillis());
            step.setErrorMessage(truncate(e.getMessage(), 300));
            stepMapper.updateById(step);
            log.info("[PERF-AI] stage=agent_action runId={} stepNo={} action={} executorDurationMs={} totalDurationMs={} outcome=error",
                    run.getId(), stepNo, decision.actionType(), actionTimer.elapsedMillis(), step.getDurationMs());
            emitter.emit(stepNo, decision.actionType().name(), "FAILED",
                    actionLabel(decision.actionType()) + "失败", null);
            // 终局失败（如文章归属校验失败）：失败 step 已落库，直接抛出内部信号收尾
            if (isTerminalFailure(e)) {
                throw new AgentRunTerminalException(stepNo, e.getMessage());
            }
            return AgentActionResult.of(tagObservation(decision, "动作执行失败：" + truncate(e.getMessage(), 200)))
                    .with("failed", true);
        }
    }

    /**
     * 给观察加**来源标注**：模型在下一轮决策时必须看得见「我做过什么动作、用什么参数」。
     *
     * V4 根因（2026-09-11 开发者面板实测）：观察里只有结果文本，模型无法确认某段内容是不是
     * 「我要查的那个查询」的产物——于是用同一个查询反复试探（同参同果 4 次，最后一次它引用了
     * 第 3 步的结果作答，说明信息早就够了，缺的只是「已经查过」这个事实）。
     *
     * 硬拦只治症状（拦得住动作，拦不住模型的不确定），标注才是治本。
     * 两者互补：标注让模型自己看得见，硬拦兜底防它仍不确定时的空转。
     */
    private String tagObservation(AgentStepDecision decision, String observation) {
        return "[" + decision.actionType().name() + queryParamLabel(decision.input()) + "]\n" + observation;
    }

    /**
     * 只带出有语义的查询参数。
     * 内部标识（articleId / anchorMode）不进观察——模型不需要，按规则也不该看到。
     */
    private String queryParamLabel(Map<String, Object> input) {
        if (input == null || input.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        appendQueryParam(sb, input, "focus");
        appendQueryParam(sb, input, "question");
        appendQueryParam(sb, input, "keyword");
        return sb.toString();
    }

    private void appendQueryParam(StringBuilder sb, Map<String, Object> input, String key) {
        Object value = input.get(key);
        if (value == null) {
            return;
        }
        String text = String.valueOf(value).trim();
        if (text.isEmpty()) {
            return;
        }
        sb.append(' ').append(key).append('=').append(truncate(text, 60));
    }

    private AgentRunResult completeWithAnswer(
            AiAgentRun run,
            AgentStepDecision decision,
            List<String> observations,
            List<AgentStepActionType> successfulActions,
            TokenUsageAccumulator stepUsage,
            long durationMs
    ) {
        String extracted = extractFinalAnswerText(decision);
        boolean realAnswer = extracted != null && !extracted.isBlank();
        // V3.12：兜底文案不是「结论」——不写锚（用 realAnswer 判断，避免比较字符串是否等于 fallback 的脆弱写法）
        String finalAnswer = realAnswer ? extracted : emptyAnswerFallback();
        recordTerminalStep(run, decision, run.getUsedSteps() + 1, stepUsage, durationMs);
        AgentRunResult result = finish(run, AiAgentRunStatus.COMPLETED, finalAnswer, observations,
                // 兜底文案不是「结论」：空答案兜底与正常回答在外层必须能区分（第四步）
                realAnswer ? AgentCompletionKind.ANSWER : AgentCompletionKind.EMPTY_ANSWER_FALLBACK);
        // V3.12：结论钩子在 **run 成功落库之后** 调用——先写锚后落库会在落库失败时留孤儿锚。
        // 失败不影响回答（钩子实现自行 fail-open），用户照常看到正文。
        if (realAnswer) {
            onAnswerConcluded(run, finalAnswer, successfulActions);
        }
        return result;
    }

    private AgentRunResult completeWithGeneratedAnswer(
            AiAgentRun run,
            AgentStepDecision decision,
            List<String> observations,
            List<AgentStepActionType> successfulActions,
            AgentRunState runState,
            AgentStepEmitter emitter,
            TokenUsageAccumulator stepUsage,
            AiStageTimer stepTimer
    ) {
        int stepNo = run.getUsedSteps() + 1;
        AiAgentStep terminalStep = recordRunningTerminalStep(run, decision, stepNo, stepUsage);
        emitter.emit(stepNo, "FINAL_ANSWER", AiAgentStepStatus.RUNNING.name(),
                "正在生成最终回答", decision.thoughtSummary());

        try {
            GeneratedAnswer generated = generateFinalAnswer(
                    run, decision, observations, runState, emitter);
            TokenUsageAccumulator generatorUsage = generated == null ? null : generated.usage();
            stepUsage.add(generatorUsage);
            AgentTokenRecorder.accumulate(run, generatorUsage);

            String finalAnswer = generated == null ? null : generated.content();
            if (finalAnswer == null || finalAnswer.isBlank()) {
                throw new AgentAnswerStreamException(
                        "文章回答生成失败：模型返回空内容", null,
                        new TokenUsageAccumulator(), false);
            }

            completeTerminalStep(run, terminalStep, stepUsage, stepTimer.elapsedMillis());
            emitter.emit(stepNo, "FINAL_ANSWER", AiAgentStepStatus.SUCCESS.name(),
                    finalAnswerSuccessMessage(), decision.thoughtSummary());
            AgentRunResult result = finish(run, AiAgentRunStatus.COMPLETED, finalAnswer, observations,
                    AgentCompletionKind.ANSWER);
            onAnswerConcluded(run, finalAnswer, successfulActions);
            return result;
        } catch (AgentAnswerStreamException e) {
            stepUsage.add(e.usage());
            AgentTokenRecorder.accumulate(run, e.usage());
            failTerminalStep(run, terminalStep, stepUsage, stepTimer.elapsedMillis(), e.getMessage());
            emitter.emit(stepNo, "FINAL_ANSWER", AiAgentStepStatus.FAILED.name(),
                    "最终回答生成失败", null);
            AgentRunResult failed = markFailed(run, "最终回答生成失败，请稍后重试");
            if (e.deliveredAnyChunk()) {
                throw e;
            }
            return failed;
        } catch (RuntimeException e) {
            failTerminalStep(run, terminalStep, stepUsage, stepTimer.elapsedMillis(), e.getMessage());
            emitter.emit(stepNo, "FINAL_ANSWER", AiAgentStepStatus.FAILED.name(),
                    "最终回答生成失败", null);
            return markFailed(run, "最终回答生成失败，请稍后重试");
        }
    }

    private AiAgentStep recordRunningTerminalStep(
            AiAgentRun run,
            AgentStepDecision decision,
            int stepNo,
            TokenUsageAccumulator stepUsage
    ) {
        AiAgentStep step = new AiAgentStep();
        step.setAgentRunId(run.getId());
        step.setStepNo(stepNo);
        step.setActionType(decision.actionType().name());
        step.setThoughtSummary(decision.thoughtSummary());
        step.setInputJson(toJson(decision.input()));
        step.setStatus(AiAgentStepStatus.RUNNING.name());
        step.setDurationMs(0L);
        applyStepTokens(step, stepUsage);
        step.setCreatedAt(LocalDateTime.now());
        stepMapper.insert(step);
        return step;
    }

    private void completeTerminalStep(
            AiAgentRun run,
            AiAgentStep step,
            TokenUsageAccumulator stepUsage,
            long durationMs
    ) {
        step.setStatus(AiAgentStepStatus.SUCCESS.name());
        step.setErrorMessage(null);
        step.setDurationMs(Math.max(0L, durationMs));
        applyStepTokens(step, stepUsage);
        stepMapper.updateById(step);
        run.setCurrentStep(step.getStepNo());
        run.setUsedSteps(step.getStepNo());
    }

    private void failTerminalStep(
            AiAgentRun run,
            AiAgentStep step,
            TokenUsageAccumulator stepUsage,
            long durationMs,
            String errorMessage
    ) {
        step.setStatus(AiAgentStepStatus.FAILED.name());
        step.setErrorMessage(truncate(errorMessage, 1000));
        step.setDurationMs(Math.max(0L, durationMs));
        applyStepTokens(step, stepUsage);
        stepMapper.updateById(step);
        run.setCurrentStep(step.getStepNo());
        run.setUsedSteps(step.getStepNo());
    }

    /**
     * V3.11：从 FINAL_ANSWER decision 提取最终回答正文（answer 键兼容回退——实测 2026-09-07：
     * 模型偶发把正文写进 message 键，内容被吞成兜底文案）。
     *
     * completeWithAnswer 与证据收敛门（Verifier 验证对象）共用同一提取，
     * 保证「验证了什么」与「实际输出什么」严格一致。提取不到返回 null（由调用方兜底）。
     */
    protected String extractFinalAnswerText(AgentStepDecision decision) {
        if (decision == null || decision.input() == null) {
            return null;
        }
        Object answer = decision.input().get("answer");
        if (answer != null && !String.valueOf(answer).isBlank()) {
            return String.valueOf(answer).trim();
        }
        Object message = decision.input().get("message");
        if (message != null && !String.valueOf(message).isBlank()) {
            return String.valueOf(message).trim();
        }
        return null;
    }

    /**
     * V3.11 保守收尾：跳过 summarize 硬答，落领域保守文案（COMPLETED，复用 finish）。
     * 触发原因已由 conservativeShutdownRequired 领域实现写日志。
     */
    private AgentRunResult completeWithConservativeShutdown(
            AiAgentRun run,
            List<String> observations
    ) {
        String finalAnswer = conservativeShutdownMessage();
        if (finalAnswer == null || finalAnswer.isBlank()) {
            finalAnswer = emptySummaryFallback();
        }
        return finish(run, AiAgentRunStatus.COMPLETED, finalAnswer, observations,
                AgentCompletionKind.CONSERVATIVE_SHUTDOWN);
    }

    private AgentRunResult markWaitingUser(
            AiAgentRun run,
            AgentStepDecision decision,
            List<String> observations,
            TokenUsageAccumulator stepUsage,
            long durationMs
    ) {
        // question 键兼容回退（与 FINAL_ANSWER 的 answer/message 同款防呆：模型偶发写 message 键）
        Object question = decision.input() == null ? null : decision.input().get("question");
        if (question == null || String.valueOf(question).isBlank()) {
            Object message = decision.input() == null ? null : decision.input().get("message");
            if (message != null && !String.valueOf(message).isBlank()) {
                question = message;
            }
        }
        String questionText = question == null || String.valueOf(question).isBlank()
                ? emptyAskUserFallback()
                : String.valueOf(question);

        recordTerminalStep(run, decision, run.getUsedSteps() + 1, stepUsage, durationMs);
        return finish(run, AiAgentRunStatus.WAITING_USER, questionText, observations, null);
    }

    /**
     * 新 Agent Run 创建时，把同 session 旧的 WAITING_WORKFLOW_CONFIRM /
     * WAITING_WRITE_CONFIRM 标记 CANCELLED（"新一轮判断开始，旧建议/提案自然过期"）。
     * confirm 接口还会再校验状态，点旧卡片无法启动 Workflow / 执行写动作。
     */
    private void cancelStalePendingSuggestions(Long sessionId) {
        if (sessionId == null) {
            return;
        }
        AiAgentRun update = new AiAgentRun();
        update.setStatus(AiAgentRunStatus.CANCELLED.name());
        update.setUpdatedAt(LocalDateTime.now());
        runMapper.update(update, new LambdaQueryWrapper<AiAgentRun>()
                .eq(AiAgentRun::getSessionId, sessionId)
                .in(AiAgentRun::getStatus,
                        AiAgentRunStatus.WAITING_WORKFLOW_CONFIRM.name(),
                        AiAgentRunStatus.WAITING_WRITE_CONFIRM.name()));
    }

    /**
     * SUGGEST_WORKFLOW 终态处理。
     *
     * 校验建议字段（workflowType allowlist / reason / initialMessage / risk 兜底），
     * 落终态 step，run 转 WAITING_WORKFLOW_CONFIRM，建议存 context_json。
     * 这里不启动任何 Workflow——启动由 confirm 接口在用户确认后完成。
     */
    private AgentRunResult suggestWorkflow(
            AiAgentRun run,
            AgentStepDecision decision,
            String goal,
            List<String> observations,
            AgentStepEmitter emitter,
            PageContextDTO pageContext,
            TokenUsageAccumulator stepUsage,
            long durationMs
    ) {
        Map<String, Object> input = decision.input() == null ? Map.of() : decision.input();
        String workflowType = text(input, "workflowType");
        if (workflowType == null || !allowedSuggestWorkflowTypes().contains(workflowType)) {
            emitter.emit(run.getUsedSteps() + 1, "SUGGEST_WORKFLOW", "FAILED",
                    "建议的流程类型不在允许范围", null);
            return markFailed(run, "Agent 建议的 Workflow 类型不在允许范围内（" + workflowType + "）");
        }
        String reason = text(input, "reason");
        if (reason == null || reason.isBlank()) {
            reason = defaultWorkflowSuggestionReason(workflowType);
        }
        String initialMessage = text(input, "initialMessage");
        if (initialMessage == null || initialMessage.isBlank()) {
            initialMessage = goal;
        }
        String risk = text(input, "risk");
        if (risk == null || risk.isBlank()) {
            risk = "MEDIUM";
        }

        AgentWorkflowSuggestion suggestion = new AgentWorkflowSuggestion(
                workflowType,
                truncate(reason, 500),
                truncate(initialMessage, 500),
                risk,
                resolveSuggestionArticleId(pageContext, run)
        );

        emitter.emit(run.getUsedSteps() + 1, "SUGGEST_WORKFLOW", "SUCCESS",
                "建议启动「" + workflowLabel(workflowType) + "」流程", decision.thoughtSummary());
        recordTerminalStep(run, decision, run.getUsedSteps() + 1, stepUsage, durationMs);
        run.setStatus(AiAgentRunStatus.WAITING_WORKFLOW_CONFIRM.name());
        // 正文直接用 reason（不带"建议启动「X」："前缀，建议卡已展示类型）
        run.setFinalAnswer(reason);
        run.setContextJson(toJson(Map.of(
                "observations", clipContext(observations),
                "pendingWorkflowSuggestion", suggestion
        )));
        run.setUpdatedAt(LocalDateTime.now());
        runMapper.updateById(run);
        log.info("Agent Run 建议 Workflow: runId={}, workflowType={}", run.getId(), workflowType);
        return AgentRunResult.of(
                run.getId(),
                AiAgentRunStatus.WAITING_WORKFLOW_CONFIRM,
                run.getFinalAnswer(),
                run.getUsedSteps(),
                run.getTotalTokens(),
                suggestion
        );
    }

    /**
     * V4 规则级重复拦截：同一动作 + 同一参数的查询重复执行，结果不会变化
     * （同 query + 同数据 = 同结果，与「第一次有没有查全」无关），属纯空转。
     *
     * 实测依据（2026-09-11 开发者只读面板观测）：一次多目标请求连发 4 次
     * `QUERY_MEMORY {"question":"写作偏好"}`，`input_json` 与 `output_json` 四次完全相同，
     * 上下文未触发裁剪（3446 &lt; 6000），且决策器 prompt 里「一次就够 / 不要重复执行完全相同的查询」
     * 两条软约束均未生效——模型无法可靠监控自己的动作历史，故改由 Runtime 硬拦。
     *
     * 边界：
     * - **只拦原样重复**：换关键词的二次查询一律放行（那才是有效探索）
     * - 只匹配 SUCCESS 的历史步，上次失败的重试不受影响
     * - 拦截落 SKIPPED step 消耗步数，避免绕过 maxSteps 形成死循环；
     *   SKIPPED 表示“系统主动省略已完成过的同参查询”，不是查询故障
     *
     * @return true 表示已拦截（调用方直接 continue）
     */
    private boolean rejectDuplicatedQuery(
            AiAgentRun run,
            AgentStepDecision decision,
            int stepNo,
            List<String> observations,
            AgentStepEmitter emitter,
            TokenUsageAccumulator stepUsage,
            AgentRunState runState,
            long durationMs
    ) {
        AiAgentStep previous = stepMapper.selectOne(new LambdaQueryWrapper<AiAgentStep>()
                .eq(AiAgentStep::getAgentRunId, run.getId())
                .eq(AiAgentStep::getActionType, decision.actionType().name())
                .eq(AiAgentStep::getStatus, AiAgentStepStatus.SUCCESS.name())
                .eq(AiAgentStep::getInputJson, toJson(decision.input()))
                .orderByAsc(AiAgentStep::getStepNo)
                .last("LIMIT 1"));
        if (previous == null) {
            return false;
        }

        emitter.emit(stepNo, decision.actionType().name(), AiAgentStepStatus.SKIPPED.name(),
                AgentStepLabelSupport.DUPLICATE_QUERY_SKIP_MESSAGE, null);
        recordSkippedStep(run, decision, stepNo,
                AgentStepLabelSupport.DUPLICATE_QUERY_SKIP_PREFIX
                        + "：同一动作 + 同一参数在第 " + previous.getStepNo() + " 步已成功执行过",
                stepUsage, durationMs);
        String duplicateHint = duplicateQueryHint(previous.getStepNo());
        observations.add(duplicateHint);
        noteFeedback(runState, duplicateHint);
        return true;
    }

    /**
     * 拦截后给决策器的观察：不光说「不许重复」，还要给出下一步能做什么
     * （只禁不疏的话，模型会换个近义词继续空转）。
     */
    private String duplicateQueryHint(int previousStepNo) {
        return "【系统提示】你重复了第 " + previousStepNo + " 步已经成功执行过的同一个查询"
                + "（动作与参数完全相同），再执行一次也不会得到新结果。"
                + "请换关键词或换角度查询；若确实没有更多可查的，直接基于已有观察作答。";
    }

    /** 重复查询等“主动省略”动作落 SKIPPED：保留审计与步数，但不伪装成执行失败。 */
    protected void recordSkippedStep(
            AiAgentRun run,
            AgentStepDecision decision,
            int stepNo,
            String reason,
            TokenUsageAccumulator stepUsage
    ) {
        recordSkippedStep(run, decision, stepNo, reason, stepUsage, 0L);
    }

    protected void recordSkippedStep(
            AiAgentRun run,
            AgentStepDecision decision,
            int stepNo,
            String reason,
            TokenUsageAccumulator stepUsage,
            long durationMs
    ) {
        AiAgentStep step = new AiAgentStep();
        step.setAgentRunId(run.getId());
        step.setStepNo(stepNo);
        step.setActionType(decision.actionType().name());
        step.setInputJson(toJson(decision.input()));
        step.setStatus(AiAgentStepStatus.SKIPPED.name());
        step.setErrorMessage(reason);
        step.setDurationMs(Math.max(0L, durationMs));
        applyStepTokens(step, stepUsage);
        step.setCreatedAt(LocalDateTime.now());
        stepMapper.insert(step);
    }

    /**
     * 被后端裁判拒绝的建议也落一条 FAILED step，保证审计完整。
     */
    protected void recordRejectedStep(
            AiAgentRun run,
            AgentStepDecision decision,
            int stepNo,
            String reason,
            TokenUsageAccumulator stepUsage
    ) {
        recordRejectedStep(run, decision, stepNo, reason, stepUsage, 0L);
    }

    protected void recordRejectedStep(
            AiAgentRun run,
            AgentStepDecision decision,
            int stepNo,
            String reason,
            TokenUsageAccumulator stepUsage,
            long durationMs
    ) {
        AiAgentStep step = new AiAgentStep();
        step.setAgentRunId(run.getId());
        step.setStepNo(stepNo);
        step.setActionType(decision.actionType().name());
        step.setInputJson(toJson(decision.input()));
        step.setStatus(AiAgentStepStatus.FAILED.name());
        step.setErrorMessage(reason);
        step.setDurationMs(Math.max(0L, durationMs));
        applyStepTokens(step, stepUsage);
        step.setCreatedAt(LocalDateTime.now());
        stepMapper.insert(step);
    }

    /**
     * 终态动作（FINAL_ANSWER / ASK_USER / SUGGEST_*）也落一条 step，保证审计完整；
     * 同时把终态步计入 usedSteps / currentStep，保证与 step 表数量一致。
     */
    protected void recordTerminalStep(
            AiAgentRun run,
            AgentStepDecision decision,
            int stepNo,
            TokenUsageAccumulator stepUsage
    ) {
        recordTerminalStep(run, decision, stepNo, stepUsage, 0L);
    }

    protected void recordTerminalStep(
            AiAgentRun run,
            AgentStepDecision decision,
            int stepNo,
            TokenUsageAccumulator stepUsage,
            long durationMs
    ) {
        AiAgentStep step = new AiAgentStep();
        step.setAgentRunId(run.getId());
        step.setStepNo(stepNo);
        step.setActionType(decision.actionType().name());
        step.setThoughtSummary(decision.thoughtSummary());
        step.setInputJson(toJson(decision.input()));
        step.setStatus(AiAgentStepStatus.SUCCESS.name());
        step.setDurationMs(Math.max(0L, durationMs));
        applyStepTokens(step, stepUsage);
        step.setCreatedAt(LocalDateTime.now());
        stepMapper.insert(step);

        run.setCurrentStep(stepNo);
        run.setUsedSteps(stepNo);
    }

    /**
     * 把本步决策的用量写到 step 行。
     *
     * 语义是「决定要走这一步」的成本——只读动作本身不调 LLM。
     * stepUsage 为 null = 该路径不做统计（子类扩展终态路径拿不到循环内的容器），记 0。
     * 注意 run 级用量不受影响：它在循环里已累加，与 step 级是否记录无关。
     */
    private void applyStepTokens(AiAgentStep step, TokenUsageAccumulator stepUsage) {
        if (stepUsage != null) {
            step.setInputTokens(stepUsage.getPromptTokens());
            step.setOutputTokens(stepUsage.getCompletionTokens());
        }
    }

    /**
     * maxSteps 到顶后的收尾回答。
     *
     * 优先用决策器总结（LLM）；总结失败/未实现时降级为拼接 observation，
     * 保证前端一定有结果。
     */
    private AgentRunResult completeWithSummary(
            AiAgentRun run,
            String goal,
            List<String> observations,
            AgentRunPolicy policy,
            AgentRunState runState
    ) {
        // P2.3（§9.3）：到顶总结同样切到结构化证据（槽位预算 ~3200），
        // 不再重放整条 observation 流（6000 上限）
        String clipped = runState != null ? runState.summaryContext() : clipContext(observations);
        String summarized = null;
        // 收尾总结的用量也归 run：它不是某一步的成本（到顶后才发生的一次调用）
        TokenUsageAccumulator summaryUsage = new TokenUsageAccumulator();
        try {
            summarized = policy.decider().summarize(goal, clipped, summaryUsage);
        } catch (Exception e) {
            log.warn("Agent 收尾总结失败，降级拼接。runId={}", run.getId(), e);
        } finally {
            AgentTokenRecorder.accumulate(run, summaryUsage);
        }

        String finalAnswer = (summarized == null || summarized.isBlank())
                ? summarizeFinalAnswer(goal, observations)
                : summarized;
        // 步数到顶的总结：有阶段性内容，但没经过最终答案同等级的证据放行（第四步）
        return finish(run, AiAgentRunStatus.COMPLETED, finalAnswer, observations,
                AgentCompletionKind.MAX_STEPS_SUMMARY);
    }

    protected String summarizeFinalAnswer(String goal, List<String> observations) {
        if (observations.isEmpty()) {
            return emptySummaryFallback();
        }
        StringBuilder sb = new StringBuilder(summaryHeader());
        for (String observation : observations) {
            sb.append("- ").append(observation).append('\n');
        }
        return clipObservation(sb.toString());
    }

    /**
     * 统一收尾出口。
     *
     * @param completionKind 第四步·阶段 1：**status = COMPLETED 的调用方必须显式填**，
     *                       否则外层任务编排收到 null 只能防御性判失败；
     *                       非 COMPLETED（WAITING_USER 等）传 null——那种情况由 status 表达。
     */
    private AgentRunResult finish(
            AiAgentRun run,
            AiAgentRunStatus status,
            String finalAnswer,
            List<String> observations,
            AgentCompletionKind completionKind
    ) {
        run.setStatus(status.name());
        run.setFinalAnswer(finalAnswer);
        run.setContextJson(toJson(clipContext(observations)));
        run.setUpdatedAt(LocalDateTime.now());
        runMapper.updateById(run);
        log.info("Agent Run 结束: runId={}, status={}, completionKind={}, usedSteps={}",
                run.getId(), status, completionKind, run.getUsedSteps());
        return AgentRunResult.of(
                run.getId(),
                status,
                finalAnswer,
                run.getUsedSteps(),
                run.getTotalTokens(),
                completionKind
        );
    }

    protected AgentRunResult markFailed(AiAgentRun run, String message) {
        run.setStatus(AiAgentRunStatus.FAILED.name());
        run.setErrorMessage(truncate(message, 1000));
        run.setUpdatedAt(LocalDateTime.now());
        runMapper.updateById(run);
        log.warn("Agent Run 失败: runId={}, reason={}", run.getId(), message);
        return AgentRunResult.of(
                run.getId(),
                AiAgentRunStatus.FAILED,
                null,
                run.getUsedSteps(),
                run.getTotalTokens()
        );
    }

    /**
     * 终态决策被后端拒绝且 run 立即失败时，补齐失败 step 的审计与 token。
     *
     * <p>普通拒绝会由调用方推进 usedSteps 后继续循环；这里是直接 FAILED 的分支，
     * 所以辅助方法同时推进 run 进度，保证 step 表和 run 的步号一致。</p>
     */
    protected AgentRunResult markFailedAfterRejectedStep(
            AiAgentRun run,
            AgentStepDecision decision,
            int stepNo,
            TokenUsageAccumulator stepUsage,
            String message
    ) {
        recordRejectedStep(run, decision, stepNo, message, stepUsage);
        run.setCurrentStep(stepNo);
        run.setUsedSteps(stepNo);
        return markFailed(run, message);
    }

    /**
     * 上下文裁剪：累计不超过 {@link #MAX_CONTEXT_CHARS}。
     *
     * <p>observations 里的每条已经是单条裁剪（{@link #MAX_OBSERVATION_CHARS}）后的，
     * 这里只按累计上限选取窗口，不再套单条裁剪（否则 6000 上限失效）。
     *
     * <p><b>2026-09-30 修正（Token 工程 P0-a）</b>：原实现从**最早**一条开始累加、满了就停，
     * 满载时丢掉的是**最新**证据——与证据收敛门「判 NEED_MORE → 补查 → 用新证据回答」的机制
     * 直接对冲。现改为「最新优先选连续窗口 + 按原时间顺序输出」，实现见 {@link AgentContextClipper}。
     */
    protected String clipContext(List<String> observations) {
        return AgentContextClipper.latestWindow(observations, MAX_CONTEXT_CHARS);
    }

    protected String clipObservation(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        return text.length() <= MAX_OBSERVATION_CHARS
                ? text
                : text.substring(0, MAX_OBSERVATION_CHARS);
    }

    protected String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }

    protected String text(Map<String, Object> input, String key) {
        if (input == null) {
            return null;
        }
        Object value = input.get(key);
        return value == null ? null : String.valueOf(value).trim();
    }

    protected String truncate(String text, int max) {
        if (text == null) {
            return null;
        }
        return text.length() <= max ? text : text.substring(0, max);
    }
}
