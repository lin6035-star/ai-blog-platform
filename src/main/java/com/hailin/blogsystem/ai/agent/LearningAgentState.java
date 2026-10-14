package com.hailin.blogsystem.ai.agent;

import com.hailin.blogsystem.entity.dto.AgentStepActionType;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 学习域 Agent 的结构化运行状态。
 *
 * <p>展示文本仍由 {@link AgentActionResult#summary()} 负责，状态只消费执行器返回的 payload。
 * 这样学习计划详情、记忆和 RAG 结果可以按查询键去重，并在固定预算内提供给下一轮决策。</p>
 */
public final class LearningAgentState implements AgentRunState {

    private static final int PLAN_BUDGET = 1800;
    private static final int MEMORY_BUDGET = 600;
    private static final int RAG_BUDGET = 800;
    private static final int ACTION_BUDGET = 300;
    private static final int MISSING_BUDGET = 300;
    private static final int FEEDBACK_BUDGET = 400;

    private final String goal;
    private final int maxSteps;
    private final Map<String, String> planFacts = new LinkedHashMap<>();
    private final Map<String, String> memoryFacts = new LinkedHashMap<>();
    private final Map<String, String> ragFacts = new LinkedHashMap<>();
    private final Set<String> emptyPlanQueries = new HashSet<>();
    private final Set<String> emptyMemoryQueries = new HashSet<>();
    private final Set<String> emptyRagQueries = new HashSet<>();
    private final EnumMap<Verdict.MissingEvidence, Long> evidenceRevisions =
            new EnumMap<>(Verdict.MissingEvidence.class);
    private final List<String> completedActions = new ArrayList<>();
    private final List<String> missingEvidence = new ArrayList<>();
    private final List<Feedback> feedbacks = new ArrayList<>();

    private LatestError latestError;
    private boolean lastStepHadNewEvidence = true;
    private Verdict.MissingEvidence rejectedEvidenceType;
    private long rejectedAtRevision;

    public LearningAgentState(String goal, int maxSteps) {
        this.goal = goal == null ? "" : goal;
        this.maxSteps = maxSteps;
    }

    @Override
    public void reduce(AgentStepDecision decision, AgentActionResult result) {
        if (decision == null || decision.actionType() == null) {
            return;
        }

        lastStepHadNewEvidence = true;
        Map<String, Object> payload = result == null ? Map.of() : result.payload();
        // 兼容旧执行器 / 测试替身：没有结构化事实时只记录动作，不猜测中文摘要的含义。
        if (payload.isEmpty()) {
            lastStepHadNewEvidence = false;
            completedActions.add(decision.actionType().name());
            return;
        }
        if (Boolean.TRUE.equals(payload.get("failed"))) {
            lastStepHadNewEvidence = false;
            completedActions.add(decision.actionType().name() + "（失败）");
            latestError = new LatestError(decision.actionType(), structuredFailure(decision, payload));
            return;
        }

        clearLatestError(decision.actionType());
        switch (decision.actionType()) {
            case QUERY_LEARNING_DASHBOARD -> reducePlan(decision, result, payload);
            case QUERY_MEMORY -> reduceMemory(decision, result, payload);
            case SEARCH_RAG -> reduceRag(decision, result, payload);
            default -> completedActions.add(decision.actionType().name());
        }
    }

    private void reducePlan(AgentStepDecision decision, AgentActionResult result, Map<String, Object> payload) {
        String status = text(payload, "status");
        String planRef = firstNonBlank(text(payload, "planRef"), text(decision.input(), "planRef"));
        String selectedPlanId = text(payload, "selectedPlanId");
        String key = "QUERY_LEARNING_DASHBOARD|" + nullToEmpty(planRef) + "|" + nullToEmpty(selectedPlanId);

        int planCount = number(payload.get("planCount"));
        int activePlanCount = number(payload.get("activePlanCount"));
        int matchedCount = number(payload.get("matchedCount"));
        String action = matchedCount > 0
                ? "已查询学习计划（匹配 " + matchedCount + " 个）"
                : "已查询学习计划（共 " + planCount + " 个，进行中 " + activePlanCount + " 个）";
        completedActions.add(action);

        if ("FAILED".equals(status)) {
            lastStepHadNewEvidence = false;
            missingEvidence.add("学习计划（查询失败，可重试）");
            latestError = new LatestError(decision.actionType(), structuredFailure(decision, payload));
            return;
        }

        String fact = planFact(payload, planRef, planCount, activePlanCount, matchedCount, selectedPlanId);
        lastStepHadNewEvidence = !Objects.equals(fact, planFacts.get(key));
        planFacts.put(key, fact);
        incrementEvidenceIfNew(Verdict.MissingEvidence.OTHER, lastStepHadNewEvidence);

        if (matchedCount == 0 && planRef != null && !planRef.isBlank()) {
            emptyPlanQueries.add("QUERY_LEARNING_DASHBOARD_EMPTY|" + planRef);
            addMissingEvidence("学习计划（没有找到与「" + planRef + "」匹配的计划）");
        } else {
            emptyPlanQueries.remove("QUERY_LEARNING_DASHBOARD_EMPTY|" + nullToEmpty(planRef));
            missingEvidence.removeIf(entry -> entry.startsWith("学习计划"));
        }
    }

    private void reduceMemory(AgentStepDecision decision, AgentActionResult result, Map<String, Object> payload) {
        String query = firstNonBlank(text(payload, "query"), text(decision.input(), "question"),
                text(decision.input(), "keyword"));
        int hitCount = number(payload.get("hitCount"));
        String status = text(payload, "status");
        String safeQuery = nullToEmpty(query);
        completedActions.add(hitCount > 0 ? "已查学习偏好（命中 " + hitCount + " 条）" : "已查学习偏好（无命中）");

        if ("FAILED".equals(status)) {
            lastStepHadNewEvidence = false;
            addMissingEvidence("学习偏好（检索失败，可重试）");
            latestError = new LatestError(decision.actionType(), structuredFailure(decision, payload));
            return;
        }
        if ("PARTIAL_FAILURE".equals(status)) {
            lastStepHadNewEvidence = false;
            if (hitCount > 0) {
                String key = "QUERY_MEMORY|" + safeQuery;
                memoryFacts.put(key, memoryFact(payload, safeQuery, hitCount));
            }
            addMissingEvidence("学习偏好（有来源检索失败，命中可能不全）");
            return;
        }

        if (hitCount > 0) {
            String key = "QUERY_MEMORY|" + safeQuery;
            String fact = memoryFact(payload, safeQuery, hitCount);
            lastStepHadNewEvidence = !Objects.equals(fact, memoryFacts.get(key));
            memoryFacts.put(key, fact);
            incrementEvidenceIfNew(Verdict.MissingEvidence.MEMORY, lastStepHadNewEvidence);
            missingEvidence.removeIf(entry -> entry.startsWith("学习偏好"));
            clearFeedback(FeedbackClearKey.MEMORY);
        } else {
            lastStepHadNewEvidence = emptyMemoryQueries.add("QUERY_MEMORY_EMPTY|" + safeQuery);
            incrementEvidenceIfNew(Verdict.MissingEvidence.MEMORY, lastStepHadNewEvidence);
            addMissingEvidence("学习偏好（记忆里没有命中，不必再查）");
        }
    }

    private void reduceRag(AgentStepDecision decision, AgentActionResult result, Map<String, Object> payload) {
        String keyword = firstNonBlank(text(payload, "keyword"), text(decision.input(), "keyword"));
        int hitCount = number(payload.get("hitCount"));
        String safeKeyword = nullToEmpty(keyword);
        completedActions.add(hitCount > 0 ? "已检索站内知识（命中 " + hitCount + " 条）" : "已检索站内知识（无命中）");

        if ("FAILED".equals(text(payload, "status"))) {
            lastStepHadNewEvidence = false;
            addMissingEvidence("站内知识（检索失败，可重试）");
            latestError = new LatestError(decision.actionType(), structuredFailure(decision, payload));
            return;
        }
        if (hitCount > 0) {
            String key = "SEARCH_RAG|" + safeKeyword;
            String fact = ragFact(payload, safeKeyword, hitCount);
            lastStepHadNewEvidence = !Objects.equals(fact, ragFacts.get(key));
            ragFacts.put(key, fact);
            incrementEvidenceIfNew(Verdict.MissingEvidence.RAG, lastStepHadNewEvidence);
            missingEvidence.removeIf(entry -> entry.startsWith("站内知识"));
            clearFeedback(FeedbackClearKey.RAG);
        } else {
            lastStepHadNewEvidence = keyword != null && !keyword.isBlank()
                    && emptyRagQueries.add("SEARCH_RAG_EMPTY|" + safeKeyword);
            incrementEvidenceIfNew(Verdict.MissingEvidence.RAG, lastStepHadNewEvidence);
            addMissingEvidence("站内知识（关键词无命中）");
        }
    }

    @Override
    public String decisionContext() {
        return renderContext(true);
    }

    @Override
    public String verificationEvidence() {
        return renderContext(false);
    }

    @Override
    public String summaryContext() {
        return renderContext(true);
    }

    private String renderContext(boolean includeOperationalState) {
        if (isEmpty(includeOperationalState)) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        appendSlot(sb, "学习计划", planFacts.values(), PLAN_BUDGET);
        appendSlot(sb, "学习偏好", memoryFacts.values(), MEMORY_BUDGET);
        appendSlot(sb, "站内知识", ragFacts.values(), RAG_BUDGET);
        appendSlot(sb, "仍缺的学习证据", missingEvidence, MISSING_BUDGET);
        if (includeOperationalState) {
            appendSlot(sb, "已完成动作", completedActions, ACTION_BUDGET);
            List<String> feedbackItems = new ArrayList<>(feedbacks.stream().map(Feedback::message).toList());
            if (latestError != null) {
                feedbackItems.add("最近失败：" + latestError.message());
            }
            appendSlot(sb, "系统反馈", feedbackItems, FEEDBACK_BUDGET);
        }
        return sb.toString();
    }

    private void appendSlot(StringBuilder sb, String title, Iterable<String> values, int budget) {
        List<String> items = new ArrayList<>();
        values.forEach(items::add);
        if (items.isEmpty()) {
            return;
        }

        List<String> kept = new ArrayList<>();
        int used = 0;
        for (int i = items.size() - 1; i >= 0; i--) {
            String item = oneLine(items.get(i));
            if (item.isBlank()) {
                continue;
            }
            if (used + item.length() > budget) {
                if (kept.isEmpty()) {
                    kept.add(0, item.substring(0, Math.max(0, budget - 1)) + "…");
                }
                break;
            }
            kept.add(0, item);
            used += item.length();
        }
        if (kept.isEmpty()) {
            return;
        }

        sb.append('【').append(title).append("】\n");
        kept.forEach(item -> sb.append(item).append('\n'));
    }

    @Override
    public void noteFeedback(String message, FeedbackClearKey clearKey) {
        String text = oneLine(message);
        if (text.isBlank()) {
            return;
        }
        for (int i = 0; i < feedbacks.size(); i++) {
            Feedback existing = feedbacks.get(i);
            if (existing.message().equals(text)) {
                if (existing.clearKey() == null && clearKey != null) {
                    feedbacks.set(i, new Feedback(text, clearKey));
                }
                return;
            }
        }
        feedbacks.add(new Feedback(text, clearKey));
    }

    @Override
    public boolean lastStepHadNewEvidence() {
        return lastStepHadNewEvidence;
    }

    @Override
    public long evidenceRevision(Verdict.MissingEvidence type) {
        Verdict.MissingEvidence normalized = type == null ? Verdict.MissingEvidence.OTHER : type;
        if (normalized == Verdict.MissingEvidence.OTHER) {
            return evidenceRevisions.values().stream().mapToLong(Long::longValue).sum();
        }
        return evidenceRevisions.getOrDefault(normalized, 0L);
    }

    @Override
    public void noteVerifierRejection(Verdict.MissingEvidence type) {
        rejectedEvidenceType = type == null ? Verdict.MissingEvidence.OTHER : type;
        rejectedAtRevision = evidenceRevision(rejectedEvidenceType);
    }

    @Override
    public boolean hasEvidenceProgressSinceRejection() {
        return rejectedEvidenceType == null
                || evidenceRevision(rejectedEvidenceType) > rejectedAtRevision;
    }

    private void incrementEvidenceIfNew(Verdict.MissingEvidence type, boolean isNew) {
        if (isNew) {
            evidenceRevisions.merge(type, 1L, Long::sum);
        }
    }

    private void addMissingEvidence(String value) {
        if (!missingEvidence.contains(value)) {
            missingEvidence.add(value);
        }
    }

    private void clearFeedback(FeedbackClearKey clearKey) {
        feedbacks.removeIf(feedback -> feedback.clearKey() == clearKey);
    }

    private void clearLatestError(AgentStepActionType actionType) {
        if (latestError != null && latestError.actionType() == actionType) {
            latestError = null;
        }
    }

    private boolean isEmpty(boolean includeOperationalState) {
        return planFacts.isEmpty() && memoryFacts.isEmpty() && ragFacts.isEmpty()
                && missingEvidence.isEmpty()
                && (!includeOperationalState || (completedActions.isEmpty()
                && feedbacks.isEmpty() && latestError == null));
    }

    private String planFact(
            Map<String, Object> payload,
            String planRef,
            int planCount,
            int activePlanCount,
            int matchedCount,
            String selectedPlanId
    ) {
        return "status=" + text(payload, "status")
                + ", planRef=" + nullToEmpty(planRef)
                + ", planCount=" + planCount
                + ", activePlanCount=" + activePlanCount
                + ", matchedCount=" + matchedCount
                + ", selectedPlanId=" + nullToEmpty(selectedPlanId);
    }

    private String memoryFact(Map<String, Object> payload, String query, int hitCount) {
        return "status=" + text(payload, "status")
                + ", query=" + query
                + ", hitCount=" + hitCount;
    }

    private String ragFact(Map<String, Object> payload, String keyword, int hitCount) {
        return "status=" + text(payload, "status")
                + ", keyword=" + keyword
                + ", hitCount=" + hitCount;
    }

    private String structuredFailure(AgentStepDecision decision, Map<String, Object> payload) {
        return decision.actionType().name() + " status=" + text(payload, "status") + "（失败，可重试）";
    }

    private String text(Map<String, Object> source, String key) {
        if (source == null) {
            return null;
        }
        Object value = source.get(key);
        return value == null ? null : String.valueOf(value).trim();
    }

    private int number(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private String oneLine(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").strip();
    }

    private record Feedback(String message, FeedbackClearKey clearKey) {
    }

    private record LatestError(AgentStepActionType actionType, String message) {
    }
}
