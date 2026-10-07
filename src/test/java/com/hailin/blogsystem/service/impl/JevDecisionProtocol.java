package com.hailin.blogsystem.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.hailin.blogsystem.entity.dto.PageContextDTO;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

final class JevDecisionProtocol {

    static final String MODEL = "typesafe/jev-1.13";
    static final double ACCEPT_THRESHOLD = 0.70;

    private static final Set<String> GOAL_QUESTIONS = Collections.unmodifiableSet(new LinkedHashSet<>(Set.of(
            "article_write",
            "article_analysis",
            "article_search",
            "article_qa",
            "workflow_create",
            "workflow_optimize",
            "workflow_learning_plan",
            "workflow_learning_progress",
            "workflow_learning_assist",
            "learning_plan_query",
            "wallet",
            "editor_action",
            "navigate"
    )));

    private JevDecisionProtocol() {
    }

    static Set<String> goalQuestionNames() {
        return GOAL_QUESTIONS;
    }

    static Set<String> questionNames() {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        names.add("primary");
        names.addAll(GOAL_QUESTIONS);
        names.add("needs_thinking");
        return Collections.unmodifiableSet(names);
    }

    static Map<String, Object> request(String message, PageContextDTO pageContext) {
        LinkedHashMap<String, Object> state = new LinkedHashMap<>();
        state.put("message", message);
        LinkedHashMap<String, Object> page = new LinkedHashMap<>();
        page.put("pageType", pageContext == null ? null : pageContext.getPageType());
        page.put("articleId", pageContext == null ? null : pageContext.getArticleId());
        state.put("pageContext", page);

        LinkedHashMap<String, Map<String, Object>> questions = new LinkedHashMap<>();
        questions.put("primary", choice(
                "选择按原话顺序出现的第一个明确目标。",
                primaryCriteria()));
        questions.put("article_write", noul(
                "独立判断消息中是否存在文章状态写动作。其他目标不能抵消，只判断这一项。",
                "明确要求点赞、取消点赞、收藏、隐藏、改标题，不包括创建或优化文章；与其他目标同时出现仍为 true",
                "整句话任何位置都没有该目标"));
        questions.put("article_analysis", noul(
                "独立判断用户是否明确要求评价、诊断或分析文章质量。仅要求优化、润色或重写，不等于要求分析。",
                "明确要求评价写得怎么样，或分析文章质量、结构、写法、优缺点；与其他目标同时出现仍为 true",
                "只要求动作、优化、润色或重写，没有单独的评价分析诉求"));
        questions.put("article_search", atomic("搜索文章", "明确要求搜索或查找相关文章"));
        questions.put("article_qa", noul(
                "独立判断是否存在针对当前文章内容的事实问答、含义解释或总结。文章质量评价不属于内容问答。",
                "询问文章讲了什么、某段含义、事实内容，或要求总结文章内容；与其他目标同时出现仍为 true",
                "要求评价文章写得怎么样、分析质量/结构/写法，或要求执行动作、搜索、优化"));
        questions.put("workflow_create", atomic("创建新文章", "明确要求写、创建或生成一篇新文章"));
        questions.put("workflow_optimize", atomic("优化已有文章", "明确要求优化、润色或重写当前或已有文章"));
        questions.put("workflow_learning_plan", atomic("制定新学习计划", "明确要求制定、创建或生成新的学习计划"));
        questions.put("workflow_learning_progress", atomic(
                "调整已有学习计划", "明确要求调整、压缩、扩展或重排已有计划或阶段"));
        questions.put("workflow_learning_assist", noul(
                "独立判断用户是否要求进入学习难点攻坚。普通概念问答不属于攻坚。",
                "用户明确表达某知识点不会、卡住或很难，并且明确要求拆练习、拆任务、攻坚或针对性讲解；两个条件必须同时满足",
                "只是问什么是、要求普通解释，或没有同时满足困难表达与攻坚请求"));
        questions.put("learning_plan_query", atomic(
                "查询已有学习计划", "询问自己的计划进度、学到哪或计划内容"));
        questions.put("wallet", atomic("钱包操作或查询", "充值、查询余额或其他明确钱包行为"));
        questions.put("editor_action", atomic(
                "编辑器保存或发布", "结合页面上下文要求保存草稿或发布"));
        questions.put("navigate", atomic(
                "页面导航", "明确要求跳转、打开文章或进入作者主页"));
        questions.put("needs_thinking", noul(
                "回答是否必须结合用户历史、当前状态、长期记忆或多步推理？",
                "离开这些上下文无法可靠回答",
                "直接概念回答或简单确定性操作即可"));

        LinkedHashMap<String, Object> request = new LinkedHashMap<>();
        request.put("model", MODEL);
        request.put("state", state);
        request.put("questions", questions);
        return request;
    }

    static JevDecisionResult mapResponse(JsonNode root, long elapsedMs) {
        JsonNode answers = requiredObject(root, "answers");
        JsonNode primary = requiredObject(answers, "primary");
        String primaryIntent = requiredText(primary, "choice");
        double primaryConfidence = requiredProbability(primary, "confidence");

        LinkedHashMap<String, Double> primaryProbabilities = new LinkedHashMap<>();
        JsonNode primaryDistribution = primary.path("probabilities");
        if (primaryDistribution.isObject()) {
            primaryDistribution.properties().forEach(entry ->
                    primaryProbabilities.put(entry.getKey(), probability(entry.getValue(),
                            "answers.primary.probabilities." + entry.getKey())));
        }

        LinkedHashMap<String, Double> goalProbabilities = new LinkedHashMap<>();
        LinkedHashSet<String> acceptedGoals = new LinkedHashSet<>();
        for (String goal : GOAL_QUESTIONS) {
            JsonNode answer = requiredObject(answers, goal);
            double value = requiredProbability(answer, "noul");
            goalProbabilities.put(goal, value);
            if (value >= ACCEPT_THRESHOLD) {
                acceptedGoals.add(goal);
            }
        }

        double needsThinking = requiredProbability(requiredObject(answers, "needs_thinking"), "noul");
        JsonNode usage = requiredObject(root, "usage");
        return new JevDecisionResult(
                root.path("model").asText(null),
                root.path("provider").asText(null),
                primaryIntent,
                primaryConfidence,
                primaryProbabilities,
                goalProbabilities,
                acceptedGoals,
                needsThinking,
                needsThinking >= ACCEPT_THRESHOLD,
                requiredInt(usage, "input_tokens"),
                requiredInt(usage, "output_tokens"),
                usage.path("cost").asDouble(0.0),
                elapsedMs);
    }

    private static Map<String, Object> atomic(String label, String trueCriteria) {
        return noul(
                "独立判断消息中是否存在：" + label + "。其他目标不能抵消，只判断这一项。",
                trueCriteria + "；与其他目标同时出现仍为 true",
                "整句话任何位置都没有该目标");
    }

    private static Map<String, Object> noul(String instructions, String trueCriteria, String falseCriteria) {
        return Map.of(
                "type", "noul",
                "instructions", instructions,
                "criteria", Map.of("true", trueCriteria, "false", falseCriteria));
    }

    private static Map<String, Object> choice(String instructions, Map<String, String> criteria) {
        return Map.of("type", "choice", "instructions", instructions, "criteria", criteria);
    }

    private static Map<String, String> primaryCriteria() {
        LinkedHashMap<String, String> criteria = new LinkedHashMap<>();
        criteria.put("general_chat", "普通闲聊、概念问答或一般建议");
        criteria.put("article_qa", "询问事实内容、含义或总结当前文章");
        criteria.put("article_write", "点赞、收藏、隐藏、改标题等文章状态动作");
        criteria.put("article_analysis", "评价文章质量、写法或结构");
        criteria.put("article_search", "搜索文章");
        criteria.put("workflow_create", "创建新文章");
        criteria.put("workflow_optimize", "优化已有文章");
        criteria.put("workflow_learning_plan", "制定新学习计划");
        criteria.put("workflow_learning_progress", "调整已有计划或阶段");
        criteria.put("workflow_learning_assist", "明确困难并要求难点攻坚");
        criteria.put("learning_plan_query", "查询已有计划进度或内容");
        criteria.put("wallet", "钱包查询或充值");
        criteria.put("editor_action", "保存或发布编辑器内容");
        criteria.put("navigate", "跳转或打开页面");
        return Collections.unmodifiableMap(criteria);
    }

    private static JsonNode requiredObject(JsonNode parent, String field) {
        JsonNode value = parent == null ? null : parent.get(field);
        if (value == null || !value.isObject()) {
            throw new IllegalArgumentException("Jev response missing object: " + field);
        }
        return value;
    }

    private static String requiredText(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new IllegalArgumentException("Jev response missing text: " + field);
        }
        return value.asText();
    }

    private static double requiredProbability(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null) {
            throw new IllegalArgumentException("Jev response missing probability: " + field);
        }
        return probability(value, field);
    }

    private static double probability(JsonNode value, String field) {
        if (!value.isNumber()) {
            throw new IllegalArgumentException("Jev response probability is not numeric: " + field);
        }
        double result = value.asDouble();
        if (result < 0.0 || result > 1.0) {
            throw new IllegalArgumentException("Jev response probability out of range: " + field);
        }
        return result;
    }

    private static int requiredInt(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || !value.canConvertToInt()) {
            throw new IllegalArgumentException("Jev response missing integer: " + field);
        }
        return value.asInt();
    }
}
