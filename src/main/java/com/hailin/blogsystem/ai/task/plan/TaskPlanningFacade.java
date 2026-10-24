package com.hailin.blogsystem.ai.task.plan;

import com.hailin.blogsystem.ai.LogValues;
import com.hailin.blogsystem.ai.task.StepType;
import com.hailin.blogsystem.ai.task.TaskPlan;
import com.hailin.blogsystem.ai.task.TaskStep;
import com.hailin.blogsystem.entity.dto.AiIntent;
import com.hailin.blogsystem.entity.dto.AiWorkflowType;
import com.hailin.blogsystem.entity.dto.ArticleInteractionAction;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 任务规划门面（第三刀·2c）——**聊天入口与编排层之间唯一的接口**。
 *
 * <p>为什么要它：路由判据属于编排层的知识，不该散进 {@code AiMessageServiceImpl}。
 * 聊天入口只调这一个方法、只看 {@link TaskRoute} 的四态，不自己判断"这个组合支不支持"。
 *
 * <p><b>路由判据</b>（设计稿 §5.1）：非任务 → CHAT；计划中每一步都有执行器 → ORCHESTRATE；
 * 已证明旧能力能完整承接 → LEGACY；其余含未迁移目标或无执行器的多目标请求 → CTA。
 * 旧路径不是兜底，只有能完整处理整个请求时才允许进入。
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class TaskPlanningFacade {

    private static final Set<String> EXPLICIT_COMPARISON_MARKERS = Set.of(
            "并比较", "再比较", "并对比", "再对比",
            "比较各", "对比各", "比较这些", "对比这些");

    /**
     * 可进入任务编排的主意图域——**白名单**（2026-10-18 域仲裁）。
     *
     * <p><b>为什么必须是白名单</b>：任务编排第一版只承接过文章域目标
     * （设计稿 §5.1「没有 Workflow、文章分析、学习域、钱包域或跨域目标」）。
     * 但实现此前只校验了"每个步骤有没有执行器"，没有校验"主意图是不是文章域"——
     * 于是分类器给出「intent=LEARNING_PLAN_QUERY + taskGoals=[ARTICLE_SEARCH]」时，
     * 一个只读的学习计划查询会被编排成站内文章搜索，并回"已全部完成"。
     *
     * <p><b>白名单优于黑名单</b>：新增域默认无权编排，避免每加一个域都要回来补黑名单。
     * 判据只拦不补——被忽略的是**模型候选**，不是用户目标（主意图仍由旧链裁决执行）。
     */
    private static final Set<String> ORCHESTRATABLE_INTENTS = Set.of(
            "ARTICLE_SEARCH", "ARTICLE_ACTION", "ARTICLE_AGENT", "ARTICLE_DETAIL_QA",
            "NAVIGATE", "EDITOR_ACTION",
            "CREATE_ARTICLE_WORKFLOW", "OPTIMIZE_ARTICLE_WORKFLOW");

    private final TaskPlanner planner;

    /**
     * 候选 → 计划 → 路由决策。
     *
     * <p>单任务与多任务走**完全相同**的这条路（单任务就是长度为 1 的计划），
     * 不额外维护第二套路径。
     */
    public TaskRoute decide(String message, AiIntent intent, Long userId) {
        TaskPlanCandidate candidate = toCandidate(message, intent);
        if (candidate == null) {
            return TaskRoute.chat("没有候选计划");
        }

        /*
         * 域仲裁（2026-10-18）：先确认主意图是否属于可编排域，再谈计划能不能执行。
         *
         * 这一步必须发生在 planner.plan() 之前：一旦计划成立且每一步都有执行器，
         * routeTask 在构造上就没有"退回旧链"的出口（ORCHESTRATE 是独占式结论）。
         * 此前"单一 ARTICLE_SEARCH 退 LEGACY"只认 intent=ARTICLE_SEARCH，恰好漏掉了
         * "只读学习查询 + 文章检索目标"这种跨域冲突；那正是生产事故的路径。
         */
        if (candidate.requestKind() == TaskPlanCandidate.RequestKind.TASK
                && !isOrchestratableDomain(intent)) {
            log.warn("[TASK-PLAN] 主意图不属于可编排域，忽略任务候选（交给既有链路）: "
                            + "intent={}, taskGoalTypes={}",
                    LogValues.safe(intent == null ? null : intent.getIntent()),
                    LogValues.safe(goalTypesOf(candidate).toString(), 120));
            return TaskRoute.legacy("主意图不属于可编排域，任务候选已忽略，交给既有链路");
        }

        PlanningResult planning = planner.plan(message, candidate);
        return switch (planning.outcome()) {
            case NOT_TASK -> TaskRoute.chat(planning.reason());
            case CTA -> TaskRoute.cta(planning.reason());
            case TASK -> routeTask(message, planning.plan(), intent);
        };
    }

    /**
     * 路由判据（设计稿 §5.1）。
     *
     * <p>**关键约束：不能让旧路径悄悄接管一个多目标请求。** 旧路径（分类器 → 单意图执行）
     * 对"点赞 + 优化并发布"这种请求只会执行点赞、然后照样声称完成——
     * 那正是这一刀要消灭的"多目标静默丢失"。所以退回旧路径必须是**确认它能完整处理**后才做的决定，
     * 不是兜底。
     */
    private TaskRoute routeTask(String message, TaskPlan plan, AiIntent intent) {
        if (!plan.hasUncoveredGoals() && plan.steps().isEmpty()) {
            // 一个目标都没识别出来——既没得编排，也没有未迁移的证据可退回
            return TaskRoute.cta("这些操作我现在还做不了。");
        }

        /*
         * 单独找文章继续走既有 RAG 问答链。
         *
         * ARTICLE_SEARCH 在 Task 中复用统一 RAG 检索，但只产出结构化证据，适合“搜索后再分析”这类复合计划；
         * 单独找文章还会经过模型回答和 STOP.references。若也送进编排器，会退化成
         * “已全部完成：找到 N 篇”的确定性汇总，并丢失推荐卡片。
         *
         * 同时校验旧 intent，避免 taskGoals 与旧分类字段冲突时误放进另一条能力链。
         */
        if (!plan.hasUncoveredGoals()
                && plan.steps().size() == 1
                && plan.steps().get(0).type() == StepType.ARTICLE_SEARCH
                && intent != null
                && "ARTICLE_SEARCH".equalsIgnoreCase(intent.getIntent())) {
            log.info("[TASK-PLAN] 单一文章搜索继续走既有 RAG 链路");
            return TaskRoute.legacy("单一文章搜索交给既有 RAG 链路");
        }

        /*
         * 5b（2026-10-01）：**单一 WORKFLOW_HANDOFF 计划继续走旧链**。
         *
         * 注册 WorkflowHandoffExecutor 之后 registry.supports(WORKFLOW_HANDOFF) 自然转真，
         * 于是"帮我优化这篇文章"会变成一个「有计划步骤、没有未覆盖目标」的请求，
         * 顺着下面的判据走进新编排器——而设计稿 §3.1 要的是：5b 第一版只接
         * 「Workflow + 其他目标」的复合请求，**单独那一条继续走已验证的旧 Workflow 链**。
         *
         * 判据刻意写成"只有一步、且就是 handoff"：只要还有别的步骤，就该进新链。
         */
        if (!plan.hasUncoveredGoals()
                && plan.steps().size() == 1
                && plan.steps().get(0).type() == StepType.WORKFLOW_HANDOFF) {
            log.info("[TASK-PLAN] 单一 Workflow 目标继续走旧链（5b 第一版不动已验证路径）");
            return TaskRoute.legacy("单一 Workflow 目标交给既有链路");
        }

        if (!plan.hasUncoveredGoals()) {
            if (explicitSearchComparisonNotInPlan(message, plan)) {
                log.info("[TASK-PLAN] 原话明确要求搜索后比较，但计划未承接比较目标，拒绝执行一半");
                return TaskRoute.cta("我识别到了搜索和比较，但这次没有把比较步骤可靠拆出来，先不执行半份任务。请再试一次。");
            }
            if (workflowSignalNotInPlan(plan, intent)) {
                log.info("[TASK-PLAN] 旧字段声明 Workflow，但计划未承接 handoff，拒绝执行一半: steps={}",
                        plan.steps().size());
                return TaskRoute.cta("这个请求里还有一条流程任务没有被可靠拆出来，先不执行半份任务。请再试一次。");
            }
            /*
             * ★ 2026-09-30 实测修复：**分类器没把分析诉求拆进 taskGoals 时，不能判 ORCHESTRATE。**
             *
             * 在线探针（真实 LLM，article-detail）实测：「点赞这篇文章，再分析一下它写得怎么样」
             * 的真实输出是 requestKind=TASK + taskGoals=[ARTICLE_WRITE]——**分析目标根本没进计划**，
             * 但第二刀的 analysisGoalEvidence 是填对的。此时 uncovered 为空，
             * 若不拦，路由会判 ORCHESTRATE，只执行点赞并回"已全部完成"，分析静默消失。
             *
             * 判据刻意读**老字段**而不是 taskGoals：老字段经过第二刀评测门验证，是可靠信号；
             * 而 taskGoals 是新字段、分类器 prompt 尚未声明 ARTICLE_ANALYSIS 取值（第四步才做）。
             */
            if (analysisGoalNotInPlan(plan, intent)) {
                if (allStepsAreWrites(plan)) {
                    // 第二刀的复合路径能整体收口这个组合（§5.2），退回不退步
                    log.info("[TASK-PLAN] 分析诉求未被计划承接，显式回退兼容链路: steps={}",
                            plan.steps().size());
                    return TaskRoute.legacy("存在未迁移目标，交给兼容链路");
                }
                // 写动作之外还有别的步骤 + 未承接的分析：没有任何现有路径能完整处理
                log.info("[TASK-PLAN] 分析诉求未被计划承接且计划含非写动作，拒绝执行一半: steps={}",
                        plan.steps().size());
                return TaskRoute.cta("这个请求里有一部分我暂时做不到，先不动手——能拆开一句一句说吗？");
            }
            /*
             * ★ 2026-10-07 实测修复：**分类器明确声明过的文章交互动作，计划里不能少。**
             *
             * 实测「点赞收藏文章，并分析一下文章，分析完文章之后再关注作者，最后跳转至作者的个人主页」
             * 同一句话两次规划出现 3 步 ↔ 5 步：漏掉的是 followAuthor（以及 NAVIGATE），
             * 都落在句子后半段。当时 uncovered=0，编排器照常执行并汇总成"已全部完成"——
             * 用户以为关注了，其实没有。这是**静默半份**，不是性能问题。
             *
             * 判据读 requestedActions 而非 taskGoals，与 analysisGoalNotInPlan 同源：
             * 摘录类字段（"用户说了哪些动作"）比结构化拆解字段（"拆成哪几步"）可靠，
             * 而后者正是漏掉的那一环。
             */
            if (requestedActionsNotInPlan(plan, intent)) {
                if (allStepsAreWrites(plan)) {
                    // 全是写动作 = 第二刀那个复合组合，兼容路径能整体收口，退回不退步
                    log.info("[TASK-PLAN] 声明的文章动作未被计划承接，显式回退兼容链路: steps={}",
                            plan.steps().size());
                    return TaskRoute.legacy("存在未迁移目标，交给兼容链路");
                }
                log.info("[TASK-PLAN] 声明的文章动作未被计划承接且计划含非写动作，拒绝执行一半: steps={}",
                        plan.steps().size());
                return TaskRoute.cta("这个请求里有几个动作我没能可靠拆出来，先不动手——能拆开一句一句说吗？");
            }
            return TaskRoute.orchestrate(plan);
        }

        if (legacyCanFullyHandle(plan)) {
            log.info("[TASK-PLAN] 未覆盖目标在兼容路径能力内，显式回退: uncoveredTypes={}",
                    safeTypes(plan));
            return TaskRoute.legacy("存在未迁移目标，交给兼容链路");
        }

        // C0.3：未承接的文章写动作，若旧 Article Agent 链具备"重新裁决"资格，显式退回。
        // 这**不是认可**那个参数（可能是 changeTitle 这类越界值）——旧链会按自己的规则重新判断
        // 对象、归属、动作与确认提案。退回错误比 CTA 更危险，所以判据刻意很窄。
        if (legacyCanReAdjudicateArticleWrite(plan, intent)) {
            log.info("[TASK-PLAN] 未承接的文章写动作交回旧链重新裁决: uncoveredTypes={}",
                    safeTypes(plan));
            return TaskRoute.legacy("存在未承接的写动作，交回旧确认链重新裁决");
        }

        // 5a：单个**已知** Workflow 目标交回旧 Workflow 链。
        // 存在的理由是"不回归"：分类器一旦开始稳定输出 WORKFLOW_HANDOFF，而该类型暂无执行器，
        // 若不退回，原本能正常启动的「帮我优化这篇文章」会被拦成 CTA。
        // 它没有放宽安全边界——旧链会按自己的规则重新裁决，且这里只放单目标、已知类型、无字段冲突。
        if (legacyCanReAdjudicateWorkflow(plan, intent)) {
            log.info("[TASK-PLAN] 单个已知 Workflow 目标交回旧 Workflow 链: uncoveredTypes={}",
                    safeTypes(plan));
            return TaskRoute.legacy("存在未承接的 Workflow 目标，交给兼容链路");
        }

        log.info("[TASK-PLAN] 多目标含未迁移部分，拒绝而不是执行一半: uncoveredTypes={}",
                safeTypes(plan));
        return TaskRoute.cta(uncoveredCtaReason(plan, intent));
    }

    /**
     * 多目标含未迁移部分时的 CTA 文案（2026-10-23）。
     *
     * <p>这句不能再写成固定的「先不动手——能拆开一句一句说吗」：用户看不懂是哪一段做不了，
     * 换一个 Workflow 或换一个任务时更是完全对不上。文案改为**按计划里真实的步骤和
     * workflowType 生成**：
     * <ul>
     *   <li>能拆出来的步骤 → 用户语言（点赞 / 收藏 / 创建文章 / 搜索文章 / 分析文章…）；</li>
     *   <li>做不了的未覆盖类型 → 同一套用户语言；</li>
     *   <li>只有一条固定边界需要说明：需要确认的流程完成之后，目前只能继续做站内搜索。</li>
     * </ul>
     *
     * <p>所以换 Workflow（OPTIMIZE_ARTICLE / CREATE_ARTICLE / 学习类）或换任务
     * （分析 / 导航 / 读取）时，句子会跟着变；查不到类型时退化成「流程 / 其他操作」，
     * **绝不把枚举名或模型原始值暴露给用户**。
     */
    private String uncoveredCtaReason(TaskPlan plan, AiIntent intent) {
        String covered = plan.steps().stream()
                .map(step -> stepUserLabel(step, intent))
                .distinct()
                .collect(Collectors.joining("、"));
        if (covered.isBlank()) {
            covered = "前面能做的部分";
        }
        String uncovered = uncoveredUserLabel(plan, intent);

        TaskStep handoff = plan.steps().stream()
                .filter(step -> step.type() == StepType.WORKFLOW_HANDOFF)
                .findFirst()
                .orElse(null);
        if (handoff == null) {
            return "这条我先不动手：我拆出来的是「" + covered + "」；但「" + uncovered
                    + "」目前没有可靠的能力承接。建议把「" + uncovered + "」单独再说一次。";
        }

        String workflow = workflowUserLabel(handoff, intent);
        List<String> beforeHandoff = new ArrayList<>();
        for (TaskStep step : plan.steps()) {
            if (step.type() == StepType.WORKFLOW_HANDOFF) {
                break;
            }
            beforeHandoff.add(stepUserLabel(step, intent));
        }
        String beforeText = beforeHandoff.stream()
                .distinct()
                .collect(Collectors.joining("、"));
        return "这条我先不动手：我拆出来的是「" + covered + "」；但「" + uncovered + "」排在「"
                + workflow + "」（需要你确认）之后，流程确认完成后目前只能继续做站内搜索，不能接着做「"
                + uncovered + "」。建议拆成两句：① 先"
                + (beforeText.isBlank() ? "做前面能做的部分" : beforeText)
                + "；② 等「" + workflow + "」确认完成后再单独说「" + uncovered + "」。";
    }

    /** 把计划步骤翻成用户语言；未知步骤类型不暴露枚举名。 */
    private String stepUserLabel(TaskStep step, AiIntent intent) {
        return switch (step.type()) {
            case ARTICLE_WRITE -> articleWriteUserLabel(step);
            case WORKFLOW_HANDOFF -> workflowUserLabel(step, intent);
            case ARTICLE_SEARCH -> "搜索文章";
            case ARTICLE_ANALYSIS -> "分析文章";
            case ARTICLE_QUERY -> "读取文章";
            case NAVIGATE -> "页面跳转";
        };
    }

    /** 写动作按 action 参数取名；参数缺失或越界时用泛化名，不泄露模型原始值。 */
    private String articleWriteUserLabel(TaskStep step) {
        Object action = step.input().get("action");
        String value = action == null ? "" : String.valueOf(action);
        return switch (value) {
            case "likeArticle" -> "点赞";
            case "favoriteArticle" -> "收藏";
            case "followAuthor" -> "关注作者";
            case "unlikeArticle" -> "取消点赞";
            case "unfavoriteArticle" -> "取消收藏";
            case "unfollowAuthor" -> "取消关注";
            default -> "文章操作";
        };
    }

    /**
     * Workflow 的用户语言：优先取步骤参数里的 workflowType（分类器原样带上来的），
     * 缺失时回退到分类器的 suggestedWorkflowType；两者都没有就只说「流程」。
     * 新增 Workflow 时在这里补一个中文名——**不补也不会把枚举名漏给用户**。
     */
    private String workflowUserLabel(TaskStep step, AiIntent intent) {
        Object fromStep = step.input().get("workflowType");
        String raw = fromStep != null
                ? String.valueOf(fromStep)
                : (intent == null ? null : intent.getSuggestedWorkflowType());
        return workflowTypeUserLabel(raw, "流程");
    }

    /** 未覆盖目标的用户语言；类型列表缺失时给一句泛化说明，不拼模型原文。 */
    private String uncoveredUserLabel(TaskPlan plan, AiIntent intent) {
        List<String> labels = plan.uncoveredTypes().stream()
                .map(type -> typeUserLabel(type, intent))
                .distinct()
                .toList();
        return labels.isEmpty() ? "剩下那部分" : String.join("、", labels);
    }

    private String typeUserLabel(String type, AiIntent intent) {
        String value = type == null ? "" : type.trim().toUpperCase(Locale.ROOT);
        return switch (value) {
            case "ARTICLE_WRITE" -> "文章操作";
            case "WORKFLOW_HANDOFF" -> workflowTypeUserLabel(
                    intent == null ? null : intent.getSuggestedWorkflowType(), "需要确认的流程");
            case "ARTICLE_SEARCH" -> "搜索文章";
            case "ARTICLE_ANALYSIS" -> "分析文章";
            case "ARTICLE_QUERY" -> "读取文章";
            case "NAVIGATE" -> "页面跳转";
            default -> "其他操作";
        };
    }

    /** 归一化后的 workflowType → 用户语言；查不到时用 fallback（当前 5b 只开放 OPTIMIZE_ARTICLE）。 */
    private String workflowTypeUserLabel(String raw, String fallback) {
        String type = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        return switch (type) {
            case "OPTIMIZE_ARTICLE" -> "优化文章";
            case "CREATE_ARTICLE" -> "创建文章";
            case "LEARNING_PLAN" -> "制定学习计划";
            case "LEARNING_PROGRESS" -> "调整学习进度";
            case "LEARNING_ASSIST" -> "学习难点攻坚";
            default -> fallback;
        };
    }

    /**
     * C0.3：未承接的**文章写动作**能否交回旧链重新裁决。
     *
     * <p>四条窄条件（缺一不可）：
     * <ol>
     *   <li>未覆盖目标**只有一项**，且类型是 {@code ARTICLE_WRITE}；</li>
     *   <li>计划里**没有其他已生成步骤**——否则旧单 Agent 会静默丢掉复合目标；</li>
     *   <li>{@code intent == ARTICLE_AGENT} 且 {@code suggestedAction == AGENT}：
     *       这只说明旧 Article Agent 链**具备重新裁决资格**；</li>
     *   <li>后续由旧链自己读用户原话、查文章、生成确认提案——这里**绝不**把
     *       {@code changeTitle} 翻译成 {@code UPDATE_ARTICLE_TITLE}。</li>
     * </ol>
     *
     * <p>为什么不读 {@code requestedActions}：那个字段只描述点赞/收藏/关注这类文章交互，
     * 改标题 / 隐藏 / 公开是由 {@code ARTICLE_AGENT → SUGGEST_WRITE} 表达的，
     * 拿它判断"旧受控写链能否承接"必然判错。
     */
    private boolean legacyCanReAdjudicateArticleWrite(TaskPlan plan, AiIntent intent) {
        if (plan.uncoveredTypes().size() != 1) {
            return false;
        }
        if (!StepType.ARTICLE_WRITE.name().equalsIgnoreCase(plan.uncoveredTypes().get(0))) {
            return false;
        }
        if (!plan.steps().isEmpty()) {
            return false;
        }
        return intent != null
                && "ARTICLE_AGENT".equalsIgnoreCase(intent.getIntent())
                && "AGENT".equalsIgnoreCase(intent.getSuggestedAction());
    }

    /**
     * 5a：单个**已知** Workflow 目标能否交回旧 Workflow 链。
     *
     * <p>条件必须同时成立（对应 5a 路由规则表）：
     * <ol>
     *   <li>未覆盖目标**只有一项**，且类型是 {@code WORKFLOW_HANDOFF}；</li>
     *   <li>计划里**没有其他已生成步骤**——否则旧链只会执行一半（「点赞 + 优化」）；</li>
     *   <li>{@code taskGoals[].params.workflowType} 必须是 {@link AiWorkflowType} 里登记的已知值；</li>
     *   <li>旧字段 {@code suggestedAction == WORKFLOW}；</li>
     *   <li>旧字段 {@code suggestedWorkflowType} 非空，且与第 3 条的类型一致。</li>
     * </ol>
     *
     * <p><b>为什么第 4、5 条是硬要求</b>：真正启动 Workflow 的不是本类，而是旧链
     * （{@code AgentPlannerSupport.decideArticleWorkflow / decideLearning}）——它先看
     * {@code isWorkflowSuggestion}（要求 {@code suggestedAction == WORKFLOW}），再看
     * {@code hasWorkflowSuggestion}（要求类型一致），任一不满足就直接降级 CTA。
     * 这里若放行旧字段残缺的请求，LEGACY 就是**假路由**：Facade 说"交给旧链"，
     * 旧链说"做不了"，用户看到的仍然是失败，只是日志显得正常。
     *
     * <p>反过来，旧链**自己**的业务拒绝（如 {@code OPTIMIZE_ARTICLE} 在无文章上下文的页面
     * 因 {@code article_context_missing} 降级 CTA）不算假路由——那正是"退回后由旧链重新裁决"
     * 的预期结果，这里不重复判断。
     *
     * <p>第 3 条同时防脏数据：分类器可能给出
     * {@code workflowType=UNKNOWN_WORKFLOW} 配 {@code suggestedWorkflowType=OPTIMIZE_ARTICLE}，
     * 那种情况一律 CTA，不挑一个信的。
     */
    private boolean legacyCanReAdjudicateWorkflow(TaskPlan plan, AiIntent intent) {
        if (plan.uncoveredTypes().size() != 1) {
            return false;
        }
        if (!StepType.WORKFLOW_HANDOFF.name().equalsIgnoreCase(plan.uncoveredTypes().get(0))) {
            return false;
        }
        if (!plan.steps().isEmpty()) {
            return false;
        }
        if (intent == null) {
            return false;
        }
        Map<String, Object> params = singleTaskGoalWorkflowParams(intent);
        if (params == null || params.size() != 1 || !params.containsKey("workflowType")) {
            return false;
        }
        String workflowType = text(params.get("workflowType"));
        if (!isKnownWorkflowType(workflowType)) {
            return false;
        }
        if (!"WORKFLOW".equalsIgnoreCase(intent.getSuggestedAction())) {
            return false;
        }
        String suggested = intent.getSuggestedWorkflowType();
        if (suggested == null || suggested.isBlank()) {
            return false;
        }
        return suggested.trim().equalsIgnoreCase(workflowType);
    }

    /**
     * 取唯一 Workflow 目标的原始参数。
     *
     * <p>5a 冻结的最小契约是 {@code params} 只能有 {@code workflowType}。这里必须在旧链回退前
     * 确定性校验，不能只靠 prompt：否则模型夹带 {@code articleId/topic/planRef} 时仍会被放进
     * LEGACY，等 5b 开始消费这些字段后就会把今天的脏候选变成明天的执行输入。
     */
    private Map<String, Object> singleTaskGoalWorkflowParams(AiIntent intent) {
        List<Map<String, Object>> goals = intent == null ? null : intent.getTaskGoals();
        if (goals == null) {
            return null;
        }
        Map<String, Object> matched = null;
        for (Map<String, Object> goal : goals) {
            if (goal == null) {
                continue;
            }
            if (!StepType.WORKFLOW_HANDOFF.name().equalsIgnoreCase(text(goal.get("type")))) {
                continue;
            }
            if (matched != null) {
                return null;
            }
            Object params = goal.get("params");
            if (params instanceof Map<?, ?> map) {
                matched = new java.util.LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (entry.getKey() != null) {
                        matched.put(String.valueOf(entry.getKey()), entry.getValue());
                    }
                }
            } else {
                return null;
            }
        }
        return matched;
    }

    /**
     * 是否是**当前旧路由确实支持**的 Workflow 类型。
     *
     * <p>这里不能只调用 {@link AiWorkflowType#valueOf(String)}。枚举值只是领域登记，
     * 并不证明 {@code AiMessageServiceImpl} 已经有对应的旧入口；如果未来只新增枚举而忘了
     * 同步旧路由，单 Workflow 退回 {@code LEGACY} 会落到没有处理分支的路径。
     * 因此用显式 switch 把「可退回旧链」作为兼容期契约，新增 Workflow 时测试会先红。
     */
    private boolean isKnownWorkflowType(String raw) {
        if (raw == null || raw.isBlank()) {
            return false;
        }
        try {
            AiWorkflowType type = AiWorkflowType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
            return switch (type) {
                case CREATE_ARTICLE, OPTIMIZE_ARTICLE, LEARNING_PLAN,
                        LEARNING_PROGRESS, LEARNING_ASSIST -> true;
            };
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * 复合请求的「分析诉求没有被计划承接」判据。
     *
     * <p>{@code analysisGoalEvidence} 非空（分类器声明"这句话里除了动作还有分析诉求"）
     * 却找不到承接它的步骤 —— 说明这个目标只存在于 {@code AiIntent} 里，没进 {@code TaskPlan}。
     * 这正是「多目标静默丢失」的入口。
     *
     * <p><b>这条判据会自动退场</b>：第四步接入 {@code ARTICLE_ANALYSIS} 执行器后，
     * 分类器 prompt 会声明该类型，目标就会变成计划里的一个步骤，本方法随即返回 false。
     * 不需要在第四步手工删除它。
     */
    private boolean analysisGoalNotInPlan(TaskPlan plan, AiIntent intent) {
        String evidence = intent == null ? null : intent.getAnalysisGoalEvidence();
        if (evidence == null || evidence.isBlank()) {
            return false;
        }
        return plan.steps().stream().noneMatch(s -> s.type() == StepType.ARTICLE_ANALYSIS);
    }

    /**
     * 分类器已经声明了某个文章交互动作，但可执行计划里没有对应步骤。
     *
     * <p><b>为什么只认六项</b>：{@code requestedActions} 的取值域比 {@code ARTICLE_WRITE}
     * 能承接的宽——它还含 {@code copyArticleLink} / {@code scrollToComments} 这类客户端动作，
     * 那些本来就不进计划。拿全量去比对会把"正常"判成"漏目标"，于是每个复合请求都被拦下。
     * 白名单直接用 {@link ArticleInteractionAction}，与规划期的参数校验同一个来源。
     *
     * <p><b>为什么读 requestedActions</b>：它是分类器对"用户说了哪些动作"的**逐字摘录**，
     * 与 {@code analysisGoalEvidence} 同性质；而 {@code taskGoals} 是结构化拆解结果，
     * 正是实测里会漏的那一环。判据只做 fail-closed，不自行补造缺失的步骤。
     *
     * <p>⚠️ 覆盖不到 {@code NAVIGATE}：{@code requestedActions} 不描述导航，
     * 而改用原文标记词（"跳转" / "主页"）会把"分析一下关注度"这类误伤。
     * 宁可少拦一种，也不引入会误杀的判据。
     */
    private boolean requestedActionsNotInPlan(TaskPlan plan, AiIntent intent) {
        if (intent == null || plan.steps().isEmpty()) {
            return false;
        }
        List<String> declared = intent.getRequestedActions();
        if (declared == null || declared.isEmpty()) {
            return false;
        }
        Set<String> planned = plan.steps().stream()
                .filter(step -> step.type() == StepType.ARTICLE_WRITE)
                .map(step -> text(step.input().get("action")))
                .filter(action -> action != null && !action.isBlank())
                .collect(Collectors.toSet());
        return declared.stream()
                .filter(action -> ArticleInteractionAction.fromClassifierValue(action).isPresent())
                .anyMatch(action -> !planned.contains(action));
    }

    /**
     * 旧分类字段已经声明存在 Workflow，但可执行计划里没有对应 handoff。
     *
     * <p>只在计划已经有其他步骤时拦截：纯单 Workflow 若 taskGoals 整体缺失，仍由既有旧链裁决；
     * 真正危险的是“点赞 + 写文章”只拆出点赞，此时新编排会提前 return，旧 Workflow 链没有机会运行。
     */
    private boolean workflowSignalNotInPlan(TaskPlan plan, AiIntent intent) {
        if (intent == null || plan.steps().isEmpty()) {
            return false;
        }
        boolean legacySignal = "WORKFLOW".equalsIgnoreCase(text(intent.getSuggestedAction()))
                || (intent.getSuggestedWorkflowType() != null
                && !intent.getSuggestedWorkflowType().isBlank());
        if (!legacySignal) {
            return false;
        }
        return plan.steps().stream().noneMatch(step -> step.type() == StepType.WORKFLOW_HANDOFF);
    }

    /**
     * 分类器可能把「搜索后比较」只拆成搜索。这里只做 fail-closed，不自行补造分析步骤。
     * 标记词刻意要求有连接语义，避免把「比较新的 Redis 文章」误判成比较任务。
     */
    private boolean explicitSearchComparisonNotInPlan(String message, TaskPlan plan) {
        boolean hasSearch = plan.steps().stream().anyMatch(s -> s.type() == StepType.ARTICLE_SEARCH);
        boolean hasAnalysis = plan.steps().stream().anyMatch(s -> s.type() == StepType.ARTICLE_ANALYSIS);
        if (!hasSearch || hasAnalysis) {
            return false;
        }
        String normalized = message == null ? "" : message.replaceAll("\\s+", "");
        return EXPLICIT_COMPARISON_MARKERS.stream().anyMatch(normalized::contains);
    }

    private boolean allStepsAreWrites(TaskPlan plan) {
        return plan.steps().stream().allMatch(s -> s.type() == StepType.ARTICLE_WRITE);
    }

    private String safeTypes(TaskPlan plan) {
        return LogValues.safe(String.valueOf(plan.uncoveredTypes()));
    }

    /**
     * 旧路径能否**完整**处理这个请求。
     *
     * <p>已知能完整处理的只有两种：
     * <ul>
     *   <li>**纯单任务未迁移**（"分析这篇文章"）——旧路径就是干这个的；</li>
     *   <li>**文章写动作 + 分析**——第二刀专门做的组合，旧复合路径能整体收口。</li>
     * </ul>
     *
     * <p>其他未覆盖类型（如 `WORKFLOW_HANDOFF`）退回后旧路径只会执行一半还声称完成，
     * 所以一律 CTA。
     */
    private boolean legacyCanFullyHandle(TaskPlan plan) {
        boolean allUncoveredAreAnalysis = !plan.uncoveredTypes().isEmpty()
                && plan.uncoveredTypes().stream()
                .allMatch(t -> StepType.ARTICLE_ANALYSIS.name().equalsIgnoreCase(t));
        if (!allUncoveredAreAnalysis) {
            return false;
        }
        // 有已覆盖步骤时：必须全是文章写动作，才是第二刀那个「动作 + 分析」组合
        return allStepsAreWrites(plan);
    }

    /**
     * 主意图是否属于任务编排可承接的域。
     *
     * <p>缺 intent（分类器失败降级）或未登记的新域一律返回 false——fail-closed：
     * 没有主意图就没有"计划归谁"的依据，此时用模型候选覆盖旧链决策正是要消灭的插队行为。
     */
    private boolean isOrchestratableDomain(AiIntent intent) {
        if (intent == null || intent.getIntent() == null) {
            return false;
        }
        return ORCHESTRATABLE_INTENTS.contains(intent.getIntent().trim().toUpperCase(Locale.ROOT));
    }

    /** 仅用于日志：候选目标类型串。不参与任何判定。 */
    private String goalTypesOf(TaskPlanCandidate candidate) {
        return candidate.goals().stream()
                .map(goal -> goal == null || goal.type() == null ? "" : goal.type())
                .filter(type -> !type.isBlank())
                .collect(Collectors.toList())
                .toString();
    }

    /** {@code AiIntent} 的候选字段 → 领域对象。字段缺失一律按"不是任务"处理 */
    private TaskPlanCandidate toCandidate(String message, AiIntent intent) {
        if (intent == null) {
            return null;
        }
        String kind = intent.getRequestKind();
        boolean isTask = "TASK".equalsIgnoreCase(kind == null ? "" : kind.trim());
        List<Map<String, Object>> rawGoals = intent.getTaskGoals();

        if (!isTask) {
            return new TaskPlanCandidate(message,
                    TaskPlanCandidate.RequestKind.CHAT, List.of(), List.of());
        }
        if (rawGoals == null || rawGoals.isEmpty()) {
            // 声称是任务却没给出目标——按"不是任务"处理，不让它进编排
            return new TaskPlanCandidate(message,
                    TaskPlanCandidate.RequestKind.CHAT, List.of(), List.of());
        }

        List<TaskPlanCandidate.CandidateGoal> goals = new ArrayList<>();
        for (Map<String, Object> raw : rawGoals) {
            if (raw == null) {
                continue;
            }
            goals.add(new TaskPlanCandidate.CandidateGoal(
                    text(raw.get("type")),
                    map(raw.get("params")),
                    texts(raw.get("goalEvidence")),
                    texts(raw.get("after")),
                    texts(raw.get("requires")),
                    text(raw.get("onFailure"))));
        }
        return new TaskPlanCandidate(message, TaskPlanCandidate.RequestKind.TASK, goals, List.of());
    }

    private String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    /** 模型可能给单个字符串也可能给数组，都要接住 */
    private List<String> texts(Object value) {
        if (value == null) {
            return List.of();
        }
        if (value instanceof String s) {
            return s.isBlank() ? List.of() : List.of(s);
        }
        if (value instanceof List<?> list) {
            List<String> result = new ArrayList<>();
            for (Object item : list) {
                if (item != null && !String.valueOf(item).isBlank()) {
                    result.add(String.valueOf(item));
                }
            }
            return result;
        }
        return List.of();
    }
}
