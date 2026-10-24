package com.hailin.blogsystem.ai.agent;

import com.hailin.blogsystem.ai.memory.AiEpisodicMemoryRetrieveService;
import com.hailin.blogsystem.ai.memory.AiMemoryRetrieveService;
import com.hailin.blogsystem.ai.rag.ArticleRagSearchService;
import com.hailin.blogsystem.config.BlogAiProperties;
import com.hailin.blogsystem.entity.AiUserMemories;
import com.hailin.blogsystem.entity.LearningPlans;
import com.hailin.blogsystem.entity.dto.ArticleRagContext;
import com.hailin.blogsystem.entity.dto.ArticleRagSearchResult;
import com.hailin.blogsystem.entity.dto.AiIntent;
import com.hailin.blogsystem.entity.dto.EpisodicMemoryRagContext;
import com.hailin.blogsystem.entity.dto.MemoryRagContext;
import com.hailin.blogsystem.entity.dto.PageContextDTO;
import com.hailin.blogsystem.entity.vo.LearningPlansDetailVO;
import com.hailin.blogsystem.service.AiUserMemoryService;
import com.hailin.blogsystem.service.LearningPlansService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Learning Agent 只读动作执行器（V1）。
 *
 * 每个 action 只调对应的 Service，返回独立 observation 文本：
 * - QUERY_LEARNING_DASHBOARD -> LearningPlansService（不整包复用 AiLearningDashboardTool，
 *   避免把记忆偷偷带进计划观察）
 * - QUERY_MEMORY             -> AiMemoryRetrieveService + AiEpisodicMemoryRetrieveService
 * - SEARCH_RAG               -> ArticleRagSearchService
 *
 * summary 保持原有展示 / 审计文本；payload 返回状态、计数和查询键等结构化事实，
 * 供 LearningAgentState 去重并按预算组装下一轮决策上下文。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class LearningAgentActionExecutorImpl implements LearningAgentActionExecutor {

    private static final int TITLE_MAX = 40;
    private static final int TEXT_MAX = 200;

    private final LearningPlansService learningPlansService;
    private final AiMemoryRetrieveService aiMemoryRetrieveService;
    private final AiEpisodicMemoryRetrieveService aiEpisodicMemoryRetrieveService;
    private final ArticleRagSearchService articleRagSearchService;
    private final BlogAiProperties blogAiProperties;
    private final AiUserMemoryService aiUserMemoryService;

    @Override
    public AgentActionResult execute(AgentStepDecision decision, Long userId, PageContextDTO pageContext) {
        if (userId == null) {
            return AgentActionResult.of("当前未登录，无法查询学习信息。")
                    .with("status", "FAILED");
        }
        return switch (decision.actionType()) {
            case QUERY_LEARNING_DASHBOARD -> queryLearningDashboard(decision, userId);
            case QUERY_MEMORY -> queryMemory(decision, userId);
            case SEARCH_RAG -> searchRag(decision);
            case QUERY_ARTICLE ->
                    throw new UnsupportedOperationException("文章域动作不经过学习域执行器");
            case ASK_USER, FINAL_ANSWER, SUGGEST_WORKFLOW, SUGGEST_WRITE,
                 ARTICLE_INTERACTION_BATCH, TASK_STEP ->
                    throw new UnsupportedOperationException("终态动作不经过执行器");
        };
    }

    /** 计划详情进入结构化事实的长度上限：状态槽位预算 1800，给元数据留余量 */
    private static final int PLAN_DETAIL_MAX = 1400;
    private static final int PLAN_CANDIDATE_MAX = 500;
    private static final int MEMORY_CONTENT_MAX = 500;
    private static final int RAG_CONTENT_MAX = 700;

    /**
     * 学习计划总览 / 详情。
     *
     * 多计划语义（与 LearningPlansServiceImpl 的“并列=歧义需追问”一致）：
     * - 点名命中唯一计划 → 返回该计划详情
     * - 点名命中多个计划 → 只列候选，不猜（Agent 下一步应 ASK_USER）
     * - 未点名且只有一个 ACTIVE → 直接返回该计划详情
     * - 未点名且多个 ACTIVE / 无 ACTIVE → 只列候选 / 总览
     *
     * <p><b>payload 必须带计划内容</b>（2026-10-18 修）：学习域 Token 优化后，
     * 决策上下文只消费结构化 payload、不再回放 summary。此前 payload 只有元数据
     * （planCount / matchedCount / selectedPlanId），模型看不到阶段和任务，
     * 于是「分析我的计划」只能回答"观察只有元数据"，然后反复查同一计划被去重跳过。
     * 现在把**有界的计划详情**放进 {@code payload.planDetail}；候选/未命中时放
     * {@code payload.planCandidates}，让模型知道下一步该重试哪个标题或向用户确认。
     */
    private AgentActionResult queryLearningDashboard(AgentStepDecision decision, Long userId) {
        String planRef = text(decision.input(), "planRef");

        List<LearningPlans> plans = learningPlansService.listByUser(userId);
        if (plans.isEmpty()) {
            return AgentActionResult.of("当前没有任何学习计划。")
                    .with("status", "SUCCESS")
                    .with("planRef", planRef)
                    .with("planCount", 0)
                    .with("activePlanCount", 0)
                    .with("matchedCount", 0)
                    .with("selectedPlanId", null);
        }

        List<LearningPlans> activePlans = plans.stream()
                .filter(plan -> LearningPlans.STATUS_ACTIVE.equals(plan.getStatus()))
                .toList();

        StringBuilder sb = new StringBuilder();
        sb.append("学习计划总览：共 ").append(plans.size())
                .append(" 个计划，").append(activePlans.size()).append(" 个进行中。\n");

        // 点名：唯一命中 → 详情；多个命中 → 候选（歧义，不猜）
        if (planRef != null && !planRef.isBlank()) {
            List<LearningPlans> matched =
                    learningPlansService.matchPlansByMessage(userId, planRef);
            if (matched.size() == 1) {
                String planDetail = buildPlanDetail(matched.get(0), userId);
                sb.append(planDetail);
                return dashboardResult(sb.toString(), planRef, plans, activePlans,
                        1, matched.get(0).getId(), planDetail, null);
            }
            if (matched.size() > 1) {
                sb.append("匹配到多个计划，请确认具体是哪个：\n");
                String planCandidates = planCandidatesText(matched);
                sb.append(planCandidates);
                return dashboardResult(sb.toString(), planRef, plans, activePlans,
                        matched.size(), null, null, planCandidates);
            }
            // 点名未命中 → 只列候选提示，不再 fallback 到唯一 ACTIVE。
            // 否则“想学 RocketMQ”会拿到《Redis 计划》详情，误导下一轮 Agent。
            sb.append("没有找到与「").append(planRef).append("」匹配的进行中计划。\n");
            String planCandidates = planCandidatesText(activePlans.isEmpty() ? plans : activePlans);
            sb.append(planCandidates);
            return dashboardResult(sb.toString(), planRef, plans, activePlans,
                    0, null, null, planCandidates);
        }

        // 未点名：唯一 ACTIVE → 详情；否则只列候选
        if (activePlans.size() == 1) {
            String planDetail = buildPlanDetail(activePlans.get(0), userId);
            sb.append(planDetail);
            return dashboardResult(sb.toString(), null, plans, activePlans,
                    1, activePlans.get(0).getId(), planDetail, null);
        }

        String planCandidates = planCandidatesText(plans);
        sb.append(planCandidates);
        return dashboardResult(sb.toString(), null, plans, activePlans, 0, null, null, planCandidates);
    }

    private AgentActionResult dashboardResult(
            String summary,
            String planRef,
            List<LearningPlans> plans,
            List<LearningPlans> activePlans,
            int matchedCount,
            Long selectedPlanId,
            String planDetail,
            String planCandidates
    ) {
        AgentActionResult result = AgentActionResult.of(summary)
                .with("status", "SUCCESS")
                .with("planRef", planRef)
                .with("planCount", plans.size())
                .with("activePlanCount", activePlans.size())
                .with("matchedCount", matchedCount)
                .with("selectedPlanId", selectedPlanId);
        if (planDetail != null && !planDetail.isBlank()) {
            result = result.with("planDetail", planDetail);
        }
        if (planCandidates != null && !planCandidates.isBlank()) {
            result = result.with("planCandidates", planCandidates);
        }
        return result;
    }

    private String planCandidatesText(List<LearningPlans> plans) {
        StringBuilder sb = new StringBuilder();
        int index = 1;
        for (LearningPlans plan : plans) {
            sb.append(index++).append(". 《").append(limit(plan.getTitle(), TITLE_MAX))
                    .append("》状态=").append(plan.getStatus()).append('\n');
        }
        return cap(sb.toString(), PLAN_CANDIDATE_MAX);
    }

    /**
     * 用户记忆检索：语义记忆 + 情景记忆，按检索词召回。
     */
    private AgentActionResult queryMemory(AgentStepDecision decision, Long userId) {
        // 检索词优先用 run 原始目标（Runtime 并入的 userGoal），其次 LLM 改写词：
        // 实测同一问题下原话 cosine=0.6241、改写 0.5607（详见 AgentStepDecision#memoryQuery）
        String query = decision.memoryQuery();
        if (query == null) {
            query = "学习偏好";
        }

        StringBuilder sb = new StringBuilder("记忆摘要：\n");
        StringBuilder content = new StringBuilder();
        int hitCount = 0;
        boolean semanticOk = true;
        boolean episodicOk = true;

        /*
         * 常驻记忆：**不计入 hitCount**（常驻是「永远有的背景」，不是检索命中；
         * 计入会让 LearningAgentState 的「记忆里没有命中，不必再查」永不触发）。
         * 读取失败也不改 semanticOk——它走 MySQL，与 ES 召回是两个故障域。
         *
         * 2026-10-13 实测：记忆向量仍留在旧 embedding 空间，与当前 query 向量
         * cos≈0.0125（近似正交），纯向量召回恒空；常驻通道与相似度无关，是兜底。
         */
        try {
            List<AiUserMemories> alwaysMemories = aiUserMemoryService.listPromptMemories(userId);
            for (AiUserMemories memory : alwaysMemories) {
                appendMemoryLine(sb, content, "- 常驻记忆(" + memory.getMemoryType() + ")："
                        + limit(memory.getContent(), TEXT_MAX));
            }
        } catch (Exception e) {
            log.warn("Agent 常驻记忆读取失败，userId={}", userId, e);
        }

        try {
            List<MemoryRagContext> memories = aiMemoryRetrieveService.retrieve(userId, query);
            if (memories != null) {
                for (MemoryRagContext memory : memories) {
                    appendMemoryLine(sb, content, "- 语义记忆(" + memory.memoryType() + ")："
                            + limit(memory.content(), TEXT_MAX));
                    hitCount++;
                }
            }
        } catch (Exception e) {
            log.warn("Agent 语义记忆检索失败，userId={}", userId, e);
            semanticOk = false;
            sb.append("- 语义记忆检索失败\n");
        }

        try {
            List<EpisodicMemoryRagContext> memories = aiEpisodicMemoryRetrieveService.retrieveForPrompt(
                    userId, blogAiProperties.getProjectKey(), query
            );
            if (memories != null) {
                for (EpisodicMemoryRagContext memory : memories) {
                    appendMemoryLine(sb, content, "- 情景记忆(" + memory.memoryType() + ")："
                            + limit(memory.content(), TEXT_MAX));
                    hitCount++;
                }
            }
        } catch (Exception e) {
            log.warn("Agent 情景记忆检索失败，userId={}", userId, e);
            episodicOk = false;
            sb.append("- 情景记忆检索失败\n");
        }

        String status = semanticOk && episodicOk
                ? "SUCCESS"
                : (semanticOk || episodicOk ? "PARTIAL_FAILURE" : "FAILED");
        AgentActionResult result = AgentActionResult.of(sb.toString())
                .with("status", status)
                .with("query", query)
                .with("hitCount", hitCount);
        /*
         * 记忆内容必须进结构化事实（2026-10-18 修）：Token 优化后模型只看 payload，
         * 只给 hitCount 等于"知道有偏好但看不到偏好是什么"，分析计划时用不上。
         */
        if (!content.isEmpty()) {
            result = result.with("memoryContent", cap(content.toString(), MEMORY_CONTENT_MAX));
        }
        return result;
    }

    private void appendMemoryLine(StringBuilder sb, StringBuilder content, String line) {
        sb.append(line).append('\n');
        content.append(line).append('\n');
    }

    /**
     * 站内文章知识检索（复用现有混合检索 + rerank 链路）。
     */
    private AgentActionResult searchRag(AgentStepDecision decision) {
        String keyword = text(decision.input(), "keyword");
        if (keyword == null || keyword.isBlank()) {
            return AgentActionResult.of("站内检索缺少关键词，无法检索。")
                    .with("status", "FAILED")
                    .with("keyword", keyword)
                    .with("hitCount", 0);
        }

        AiIntent intent = new AiIntent();
        intent.setIntent("ARTICLE_SEARCH");
        intent.setKeyWord(keyword);

        ArticleRagSearchResult result = articleRagSearchService.search(keyword, intent);
        if (result.contexts() == null || result.contexts().isEmpty()) {
            return AgentActionResult.of("站内没有检索到与「" + keyword + "」相关的文章知识。")
                    .with("status", "SUCCESS")
                    .with("keyword", keyword)
                    .with("hitCount", 0);
        }

        StringBuilder sb = new StringBuilder("站内文章知识检索结果（")
                .append(result.strategy()).append("）：\n");
        StringBuilder content = new StringBuilder();
        int index = 1;
        for (ArticleRagContext context : result.contexts()) {
            String line = index++ + ". 《" + limit(context.title(), TITLE_MAX)
                    + "》(" + context.articleId() + ")：" + limit(context.content(), TEXT_MAX);
            sb.append(line).append('\n');
            content.append(line).append('\n');
        }
        AgentActionResult actionResult = AgentActionResult.of(sb.toString())
                .with("status", "SUCCESS")
                .with("keyword", keyword)
                .with("hitCount", result.contexts().size());
        /*
         * RAG 片段必须进结构化事实（2026-10-18 修）：Token 优化后模型只看 payload，
         * 只给 hitCount 等于"知道命中几篇但看不到内容"，无法据此分析。
         */
        if (!content.isEmpty()) {
            actionResult = actionResult.with("ragContent", cap(content.toString(), RAG_CONTENT_MAX));
        }
        return actionResult;
    }

    /**
     * 计划详情文本（展示摘要与结构化事实共用一份，避免两处漂移）。
     *
     * <p>超长截断：分析类任务需要看到阶段/任务，但状态槽位有预算；
     * 截断后给模型一个"后略"信号，它知道信息不全时仍可提问。
     */
    private String buildPlanDetail(LearningPlans plan, Long userId) {
        StringBuilder sb = new StringBuilder();
        sb.append("目标计划《").append(limit(plan.getTitle(), TITLE_MAX))
                .append("》状态=").append(plan.getStatus()).append('\n');

        LearningPlansDetailVO detail;
        try {
            detail = learningPlansService.getDetail(plan.getId(), userId);
        } catch (Exception e) {
            log.warn("Agent 读取计划详情失败，planId={}", plan.getId(), e);
            return cap(sb.toString(), PLAN_DETAIL_MAX);
        }
        if (detail == null) {
            return cap(sb.toString(), PLAN_DETAIL_MAX);
        }

        sb.append("总进度：").append(detail.getDoneTasks()).append('/')
                .append(detail.getTotalTasks()).append('\n');
        if (detail.getStages() == null) {
            return cap(sb.toString(), PLAN_DETAIL_MAX);
        }
        for (LearningPlansDetailVO.StageProgress stage : detail.getStages()) {
            sb.append("- 阶段《").append(limit(stage.getTitle(), TITLE_MAX)).append("》：");
            if (stage.getTasks() == null || stage.getTasks().isEmpty()) {
                sb.append("（无任务）\n");
                continue;
            }
            for (LearningPlansDetailVO.TaskItem task : stage.getTasks()) {
                sb.append(task.isDone() ? "[x] " : "[ ] ")
                        .append(limit(task.getTitle(), TITLE_MAX)).append("；");
            }
            sb.append('\n');
        }
        return cap(sb.toString(), PLAN_DETAIL_MAX);
    }

    /** 有界截断：不静默丢内容，留"后略"标记 */
    private String cap(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max) + "…（后略）";
    }

    private String text(Map<String, Object> input, String key) {
        if (input == null) {
            return null;
        }
        Object value = input.get(key);
        return value == null ? null : String.valueOf(value).trim();
    }

    private String limit(String text, int max) {
        if (text == null || text.isBlank()) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max);
    }
}
