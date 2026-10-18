package com.hailin.blogsystem.ai.classifier.jev;

import com.hailin.blogsystem.entity.dto.AiIntent;
import com.hailin.blogsystem.entity.dto.PageContextDTO;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

@Component
public final class JevFastPathMapper {

    private static final double ACCEPT_THRESHOLD = 0.70;

    /**
     * 放行的只读意图：Jev goal → 分类器 intent 名。
     *
     * <p>放行标准不是"Jev 判得准"，而是<b>判错的代价可控</b>：这些都只读，
     * 最坏结果是答非所问，用户重说一遍即可。
     *
     * <p>{@code learning_plan_query} 是 2026-10-04 补的——第一批只挑了文章域三个，
     * 把同样是纯只读的学习计划查询漏在了外面（Jev 协议里有它、16 例评测 3/3 全对、
     * Planner 也早有现成的 dashboard TOOL 分支，仅仅是白名单没开）。
     * 它的 {@code suggestedAction} 取 {@code TOOL} 而非 {@code CHAT}，与 plus 保持一致。
     */
    private static final Map<String, String> READ_ONLY_INTENTS = Map.of(
            "article_qa", "ARTICLE_DETAIL_QA",
            "article_analysis", "ARTICLE_DETAIL_QA",
            "article_search", "ARTICLE_SEARCH",
            "learning_plan_query", "LEARNING_PLAN_QUERY"
    );

    /**
     * 需要"当前文章上下文"才放行的只读意图——缺上下文时回退 plus。
     *
     * <p>文章问答 / 分析脱离文章页就没有对象，放行只会得到一句缺少对象的回答；
     * 而 {@code article_search} 与 {@code learning_plan_query} 不依赖页面上下文。
     */
    private static final Set<String> NEEDS_CURRENT_ARTICLE =
            Set.of("article_qa", "article_analysis");

    /**
     * 放行的 Workflow 意图：Jev goal → {@code suggestedWorkflowType}。
     *
     * <p><b>2026-10-18 起学习类 Workflow 全部移出快速路径</b>（LEARNING_PLAN /
     * LEARNING_PROGRESS / LEARNING_ASSIST）。原判断是"确认型 Workflow 判错只是多问一次"，
     * 实测不成立：用户说「讲讲我的 Redis 学习计划」「讲一讲我的 Redis 学习计划」，
     * Jev 以 0.83 / 0.76 判成 {@code workflow_learning_plan} 并直接拉起 LEARNING_PLAN 工作流——
     * 实际代价是 17 秒完整规划 + 一张错误的新计划确认卡 + 真实计费，用户以为系统要重建计划。
     *
     * <p><b>为什么靠移出白名单而不是加规则</b>：Jev 是 decision 模型，goal 是独立二元判据，
     * 没有"只读讲解"这一类目标可承接；「看/讲」与「制定」的语义边界无法由二元概率可靠区分
     * （单看"学习计划"这个对象就会判真）。写意图交给 plus 分类器（有完整语义分界 + 在线评测门），
     * 只读意图继续走快速路径——判错只影响一次回答，这是本来就成立的代价不对称。
     *
     * <p>保留 {@code workflow_create} / {@code workflow_optimize}：动词明确（写/创建/优化），
     * 且与只读意图（article_qa / article_analysis）在 Jev 判据里是分开的。
     *
     * <p>键与值都必须与分类器既有协议逐字一致——下游 {@code AgentPlannerSupport} 用
     * {@code suggestedWorkflowType.equals(workflowType.name())} 判定，错一个字就降级 CTA。
     */
    private static final Map<String, String> WORKFLOW_TYPES = Map.of(
            "workflow_create", "CREATE_ARTICLE",
            "workflow_optimize", "OPTIMIZE_ARTICLE"
    );

    public JevFastPathDecision map(JevDecisionResult result, PageContextDTO pageContext) {
        if (result == null) {
            return JevFastPathDecision.fallback("INVALID_RESPONSE");
        }

        Set<String> goals = result.acceptedGoals();
        if (goals.size() > 1) {
            return JevFastPathDecision.fallback("MULTIPLE_GOALS");
        }
        if (goals.isEmpty()) {
            return mapGeneralChat(result);
        }

        String goal = goals.iterator().next();

        // Workflow 走独立出口：它的字段契约与只读意图不同（suggestedAction=WORKFLOW
        // + suggestedWorkflowType），不能复用 chatIntent 那套"全 CHAT"的最小字段
        if (WORKFLOW_TYPES.containsKey(goal)) {
            return mapWorkflow(result, goal, pageContext);
        }

        String intentName = READ_ONLY_INTENTS.get(goal);
        if (intentName == null) {
            return JevFastPathDecision.fallback("UNSUPPORTED_GOAL");
        }
        if (!goal.equals(result.primaryIntent())) {
            return JevFastPathDecision.fallback("PRIMARY_CONFLICT");
        }

        double confidence = result.goalProbabilities().getOrDefault(goal, 0.0);
        if (confidence < ACCEPT_THRESHOLD) {
            return JevFastPathDecision.fallback("LOW_CONFIDENCE");
        }
        if (NEEDS_CURRENT_ARTICLE.contains(goal) && !hasCurrentArticle(pageContext)) {
            return JevFastPathDecision.fallback("ARTICLE_CONTEXT_MISSING");
        }

        // 学习计划查询由 Planner 转成 dashboard 读工具，plus 给它的 suggestedAction 是 TOOL 而非 CHAT。
        // Planner 当前只看 intent（AgentPlannerSupport「LEARNING_PLAN_QUERY」分支），
        // 但这里对齐语义——将来判据若收紧到 suggestedAction，不会因为这一处而静默变成普通聊天。
        String suggestedAction = "LEARNING_PLAN_QUERY".equals(intentName) ? "TOOL" : "CHAT";
        return JevFastPathDecision.accepted(chatIntent(
                intentName, confidence, false, "Jev 单目标只读快速路径", suggestedAction));
    }

    /**
     * Workflow 出口。
     *
     * <p>与只读出口共用三项前置：单目标（在 {@link #map} 里已保证）、
     * primary 一致、置信度达门槛。多出来的只有文章优化的上下文要求。
     */
    private JevFastPathDecision mapWorkflow(JevDecisionResult result, String goal,
                                            PageContextDTO pageContext) {
        if (!goal.equals(result.primaryIntent())) {
            return JevFastPathDecision.fallback("PRIMARY_CONFLICT");
        }

        double confidence = result.goalProbabilities().getOrDefault(goal, 0.0);
        if (confidence < ACCEPT_THRESHOLD) {
            return JevFastPathDecision.fallback("LOW_CONFIDENCE");
        }

        String workflowType = WORKFLOW_TYPES.get(goal);

        /*
         * 文章优化缺当前文章时回退 plus，而不是放行后由 Planner 降级 CTA。
         *
         * 两条路的最终结果都是"问用户要文章"，但 plus 对缺上下文的处理有更完整的语义
         * （可能结合会话锚找到用户刚提过的那篇），而放行只会得到一句缺少上下文的追问。
         * 与 article_qa / article_analysis 的处理保持一致。
         */
        if ("OPTIMIZE_ARTICLE".equals(workflowType) && !hasCurrentArticle(pageContext)) {
            return JevFastPathDecision.fallback("ARTICLE_CONTEXT_MISSING");
        }

        return JevFastPathDecision.accepted(workflowIntent(workflowType, confidence));
    }

    /**
     * Workflow 意图的字段契约——每个字段都对应下游一处硬判据，缺一个就会被降级：
     *
     * <ul>
     *   <li>{@code suggestedAction=WORKFLOW}：{@code isWorkflowSuggestion} 的唯一判据</li>
     *   <li>{@code suggestedWorkflowType}：{@code hasWorkflowSuggestion} 要求等于
     *       {@code AiWorkflowType.name()}</li>
     *   <li>{@code risk=LOW}：学习类 Workflow 要求 {@code isLowRisk}（会话锚可兜底）；
     *       文章类接受 LOW/MEDIUM，LOW 两边都安全</li>
     *   <li>{@code requestKind=CHAT}：不进 TaskPlan 编排，走<b>已验证的旧 Workflow 路径</b>
     *       （第三刀 TaskPlan 面向多目标任务，单目标走旧链更保守）</li>
     * </ul>
     *
     * <p>不设置 {@code topic} / {@code topicEvidence}：创建文章的 requirement 用的是
     * <b>用户原话</b>（{@code AiMessageServiceImpl} 明确注释"LLM 判断意图、规则判断参数"），
     * 主题是否明确由 {@code CreateArticleWorkflowHandler.isRequirementUnclear} 确定性判断，
     * 不依赖这里输出。
     */
    private AiIntent workflowIntent(String workflowType, double confidence) {
        AiIntent intent = new AiIntent();
        intent.setIntent(intentNameOf(workflowType));
        intent.setConfidence(confidence);
        intent.setSuggestedAction("WORKFLOW");
        intent.setSuggestedWorkflowType(workflowType);
        intent.setRisk("LOW");
        intent.setRequestKind("CHAT");
        intent.setReason("Jev 单目标 Workflow 快速路径");
        // needsThinking 只对 GENERAL_CHAT 生效，Workflow 恒 false
        intent.setNeedsThinking(false);
        return intent;
    }

    /**
     * workflowType → 分类器旧字段的 intent 名。
     *
     * <p>与 {@code AiIntentClassifierImpl.intentForWorkflowType} 必须逐字一致：
     * 文章类带 {@code _WORKFLOW} 后缀、学习类不带——这是既有协议，不是笔误。
     */
    private static String intentNameOf(String workflowType) {
        return switch (workflowType) {
            case "CREATE_ARTICLE" -> "CREATE_ARTICLE_WORKFLOW";
            case "OPTIMIZE_ARTICLE" -> "OPTIMIZE_ARTICLE_WORKFLOW";
            default -> workflowType;
        };
    }

    private JevFastPathDecision mapGeneralChat(JevDecisionResult result) {
        if (!"general_chat".equals(result.primaryIntent())) {
            return JevFastPathDecision.fallback("UNSUPPORTED_GOAL");
        }
        if (result.primaryConfidence() < ACCEPT_THRESHOLD) {
            return JevFastPathDecision.fallback("LOW_CONFIDENCE");
        }
        return JevFastPathDecision.accepted(chatIntent(
                "GENERAL_CHAT",
                result.primaryConfidence(),
                result.needsThinking(),
                "Jev 普通聊天快速路径"));
    }

    private AiIntent chatIntent(String intentName, double confidence,
                                boolean needsThinking, String reason) {
        return chatIntent(intentName, confidence, needsThinking, reason, "CHAT");
    }

    private AiIntent chatIntent(String intentName, double confidence,
                                boolean needsThinking, String reason,
                                String suggestedAction) {
        AiIntent intent = new AiIntent();
        intent.setIntent(intentName);
        intent.setConfidence(confidence);
        intent.setRequestKind("CHAT");
        intent.setSuggestedAction(suggestedAction);
        intent.setRisk("LOW");
        intent.setReason(reason);
        intent.setNeedsThinking(needsThinking);
        intent.setNeedsThinkingReason(needsThinking ? "Jev 判断需要结合上下文" : null);
        return intent;
    }

    private boolean hasCurrentArticle(PageContextDTO pageContext) {
        return pageContext != null
                && "article-detail".equals(pageContext.getPageType())
                && pageContext.getArticleId() != null
                && !pageContext.getArticleId().isBlank();
    }
}
