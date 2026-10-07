package com.hailin.blogsystem.ai.agent;

import com.hailin.blogsystem.entity.dto.AgentStepActionType;

import java.util.*;

/**
 * 文章域结构化运行状态（P2.2，Token 设计稿 §7.2 状态模型）。
 *
 * <p><b>它替代什么</b>：以前每一步都把整条 observation 流重新裁剪后塞给 Decider——
 * 里面大部分是**已完成动作的完整说明**与**已被新证据替换的旧结果**。
 * 这里把执行结果按语义槽位归约，决策上下文只带当前真正需要的部分（§7.6）。
 *
 * <p><b>只吃 payload</b>：所有判断都读 {@link AgentActionResult#payload()}，不解析中文文案。
 * 以 {@code focusHit} 为例——聚焦命中与未命中都会返回一段文本，只有结构化字段能区分，
 * 这正是 §7.3 否掉「解析 observation」那条路的原因。
 *
 * <p><b>per-run</b>：由 {@code ArticleAgentRuntime#newRunState} 每次 run 新建，
 * 不放在单例字段上（否则并发请求串号）。
 */
public final class ArticleAgentState implements AgentRunState {

    /** §7.4 槽位预算：先按语义槽位整理，再按槽位裁剪（不是把总预算简单调小） */
    private static final int ARTICLE_BUDGET = 1200;
    private static final int MEMORY_BUDGET = 600;
    private static final int RAG_BUDGET = 800;
    private static final int ACTION_BUDGET = 300;
    private static final int MISSING_BUDGET = 300;
    /** 反馈同样占预算——不设上限的话「上下文约 3200」就不成立 */
    private static final int FEEDBACK_BUDGET = 400;

    private final String goal;
    private final int maxSteps;

    private int stepNo;

    // ---- target ----
    private String articleId;
    private String articleTitle;
    private Boolean owned;

    // ---- evidence（§7.5：键去重，同键新内容替换旧内容）----
    private final Map<String, String> articleEvidence = new LinkedHashMap<>();
    private final Map<String, String> memories = new LinkedHashMap<>();
    private final Map<String, String> ragFacts = new LinkedHashMap<>();
    private final Set<String> articleEmptyQueries = new HashSet<>();
    private final Set<String> memoryEmptyQueries = new HashSet<>();
    private final Set<String> ragEmptyQueries = new HashSet<>();
    private final EnumMap<Verdict.MissingEvidence, Long> evidenceRevisions =
            new EnumMap<>(Verdict.MissingEvidence.class);
    private Verdict.MissingEvidence rejectedEvidenceType;
    private long rejectedAtRevision;

    // ---- actions ----
    private final List<String> completedActions = new ArrayList<>();
    private final List<String> missingEvidence = new ArrayList<>();
    private LatestError latestError;

    /** P2.3：本步有没有产出新证据（Verifier 的 R3 放行规则用它，替代失效的文本比对） */
    private boolean lastStepHadNewEvidence = true;

    public ArticleAgentState(String goal, int maxSteps) {
        this.goal = goal == null ? "" : goal;
        this.maxSteps = maxSteps;
    }

    @Override
    public void reduce(AgentStepDecision decision, AgentActionResult result) {
        if (decision == null || decision.actionType() == null) {
            return;
        }
        stepNo++;
        clearFeedback(FeedbackClearKey.QUERY_REQUIRED);
        // 每步先假定"有新证据"；只有检测到同键同内容才置 false（保守方向：宁可不放行）
        lastStepHadNewEvidence = true;
        Map<String, Object> payload = result == null ? Map.of() : result.payload();

        if (Boolean.TRUE.equals(payload.get("failed"))) {
            lastStepHadNewEvidence = false;
            completedActions.add(decision.actionType().name() + "（失败）");
            latestError = new LatestError(decision.actionType(), oneLine(result.summary()));
            return;
        }

        clearLatestError(decision.actionType());

        switch (decision.actionType()) {
            case QUERY_ARTICLE -> reduceArticle(decision, result, payload);
            case QUERY_MEMORY -> reduceMemory(decision, result, payload);
            case SEARCH_RAG -> reduceRag(decision, result, payload);
            default -> completedActions.add(decision.actionType().name());
        }
    }

    private void reduceArticle(AgentStepDecision decision, AgentActionResult result, Map<String, Object> payload) {
        articleId = asString(payload.get("articleId"));
        articleTitle = asString(payload.get("articleTitle"));
        owned = payload.get("owned") instanceof Boolean b ? b : null;

        String focus = asString(payload.get("focus"));
        boolean focusHit = Boolean.TRUE.equals(payload.get("focusHit"));
        boolean noFocus = payload.get("focusHit") == null;

        completedActions.add(noFocus
                ? "已读文章结构"
                : (focusHit ? "已查「" + focus + "」" : "查过「" + focus + "」但没找到"));

        if (noFocus || focusHit) {
            // 键含 focus：不同 focus 允许并存（受槽位预算限制），同一个 focus 的新结果替换旧的
            String key = "QUERY_ARTICLE|" + articleId + "|" + (noFocus ? "" : focus);
            lastStepHadNewEvidence = !Objects.equals(result.summary(), articleEvidence.get(key));
            articleEvidence.put(key, result.summary());
            incrementEvidenceIfNew(Verdict.MissingEvidence.ARTICLE_CONTENT, lastStepHadNewEvidence);
            missingEvidence.removeIf(entry -> entry.startsWith("文章内容"));
            // 正文补到了：指向「文章内容」的旧反馈（如 Verifier 的"缺正文细节"）必须一起清，
            // 否则下一轮 Verifier 又看到"证据不足"，形成自我强化
            clearFeedback(FeedbackClearKey.ARTICLE_CONTENT);
        } else {
            String emptyKey = "QUERY_ARTICLE_EMPTY|" + articleId + "|" + focus;
            lastStepHadNewEvidence = articleEmptyQueries.add(emptyKey);
            incrementEvidenceIfNew(Verdict.MissingEvidence.ARTICLE_CONTENT, lastStepHadNewEvidence);
            missingEvidence.add("文章内容（用户问的那部分没找到，需要换说法或换 focus）");
        }
    }

    private void reduceMemory(AgentStepDecision decision, AgentActionResult result, Map<String, Object> payload) {
        int hits = asInt(payload.get("hitCount"));
        String query = asString(payload.get("query"));
        String status = asString(payload.get("status"));

        // P2.3 修订：检索**失败**不能被说成"没有命中"——那会告诉模型"不必再查"，
        // 而真实情况是这次查询根本没跑成（语义记忆 / 情景记忆是两个来源，可能只挂了一个）。
        if ("FAILED".equals(status)) {
            lastStepHadNewEvidence = false;
            completedActions.add("查写作偏好失败");
            missingEvidence.add("写作偏好（检索服务失败，可重试）");
            latestError = new LatestError(decision.actionType(), oneLine(result.summary()));
            return;
        }
        if ("PARTIAL_FAILURE".equals(status)) {
            lastStepHadNewEvidence = false;
            completedActions.add("查写作偏好部分失败（命中 " + hits + " 条）");
            if (hits > 0) {
                String key = "QUERY_MEMORY|" + query;
                memories.put(key, result.summary());
            }
            missingEvidence.add("写作偏好（有来源检索失败，命中可能不全）");
            return;
        }

        completedActions.add(hits > 0 ? "已查写作偏好（命中 " + hits + " 条）" : "已查写作偏好（无命中）");
        if (hits > 0) {
            String key = "QUERY_MEMORY|" + query;
            lastStepHadNewEvidence = !Objects.equals(result.summary(), memories.get(key));
            memories.put(key, result.summary());
            incrementEvidenceIfNew(Verdict.MissingEvidence.MEMORY, lastStepHadNewEvidence);
            missingEvidence.removeIf(entry -> entry.startsWith("写作偏好"));
            clearFeedback(FeedbackClearKey.MEMORY);
        } else {
            lastStepHadNewEvidence = memoryEmptyQueries.add("QUERY_MEMORY_EMPTY|" + query);
            incrementEvidenceIfNew(Verdict.MissingEvidence.MEMORY, lastStepHadNewEvidence);
            missingEvidence.add("写作偏好（记忆里没有命中，不必再查）");
        }
    }

    private void reduceRag(AgentStepDecision decision, AgentActionResult result, Map<String, Object> payload) {
        int hits = asInt(payload.get("hitCount"));
        String keyword = asString(payload.get("keyword"));
        completedActions.add(hits > 0 ? "已检索站内知识（命中 " + hits + " 条）" : "已检索站内知识（无命中）");

        if (hits > 0) {
            String key = "SEARCH_RAG|" + keyword;
            lastStepHadNewEvidence = !Objects.equals(result.summary(), ragFacts.get(key));
            ragFacts.put(key, result.summary());
            incrementEvidenceIfNew(Verdict.MissingEvidence.RAG, lastStepHadNewEvidence);
            // P2.3 修订：换关键词命中后必须清掉旧的"无命中"——Memory / Article 都有清理，RAG 原先漏了，
            // 缺失项只增不减，模型会一直以为"站内查不到"。
            missingEvidence.removeIf(entry -> entry.startsWith("站内知识"));
            clearFeedback(FeedbackClearKey.RAG);
        } else {
            lastStepHadNewEvidence = keyword != null && !keyword.isBlank()
                    && ragEmptyQueries.add("SEARCH_RAG_EMPTY|" + keyword);
            incrementEvidenceIfNew(Verdict.MissingEvidence.RAG, lastStepHadNewEvidence);
            missingEvidence.add("站内知识（关键词无命中）");
        }
    }

    private void incrementEvidenceIfNew(Verdict.MissingEvidence type, boolean isNew) {
        if (isNew) {
            evidenceRevisions.merge(type, 1L, Long::sum);
        }
    }

    @Override
    public long evidenceRevision(Verdict.MissingEvidence type) {
        Verdict.MissingEvidence normalized = type == null ? Verdict.MissingEvidence.OTHER : type;
        if (normalized == Verdict.MissingEvidence.OTHER) {
            return evidenceRevisions.getOrDefault(Verdict.MissingEvidence.ARTICLE_CONTENT, 0L)
                    + evidenceRevisions.getOrDefault(Verdict.MissingEvidence.MEMORY, 0L)
                    + evidenceRevisions.getOrDefault(Verdict.MissingEvidence.RAG, 0L);
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

    /**
     * §7.6：只带「这一步决策真正需要的」——目标、步数、已确认对象、证据槽位、
     * 已完成动作、缺失证据、最近失败。不再发送已完成动作的完整说明与旧 observation。
     */
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
        return decisionContext();
    }

    private String renderContext(boolean includeOperationalState) {
        // 目标与步数**不在这里重复**：Decider 的 user prompt 已经单独输出「目标：」「当前步数：」
        // （LlmAgentStepDecider#buildUserPrompt），再写一遍就是重复计费。
        // §7.6 列的是整条 user prompt 的组成，不是本槽位的组成。
        //
        // 还没执行任何动作时返回空串：与旧路径 clipContext(空列表) 的行为一致，
        // 不凭空给第 1 步加格式开销（实测那会让 2 步 run 的输入反增）。
        if (includeOperationalState ? isEmpty() : isVerificationEvidenceEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();

        if (articleId != null) {
            sb.append("【已确认目标文章】ID ").append(articleId);
            if (articleTitle != null && !articleTitle.isBlank()) {
                sb.append("《").append(articleTitle).append('》');
            }
            if (Boolean.FALSE.equals(owned)) {
                sb.append("（不是你的文章：只能分析和给建议，不能提议修改）");
            }
            sb.append('\n');
        }

        appendSlot(sb, "文章证据", articleEvidence.values(), ARTICLE_BUDGET);
        appendSlot(sb, "记忆证据", memories.values(), MEMORY_BUDGET);
        appendSlot(sb, "站内知识", ragFacts.values(), RAG_BUDGET);
        appendSlot(sb, "仍缺的证据", missingEvidence, MISSING_BUDGET);
        if (includeOperationalState) {
            appendSlot(sb, "已完成动作", completedActions, ACTION_BUDGET);
            // P2.3 修订：反馈**独立成槽且只出现一次**——原先同时塞 missingEvidence 与 latestError，
            // 同一句在 prompt 里出现两遍，而且 latestError 逃出了所有预算约束
            appendSlot(sb, "系统反馈", feedbacks.stream().map(Feedback::message).toList(), FEEDBACK_BUDGET);

            if (latestError != null) {
                sb.append("【最近失败】").append(latestError.message()).append('\n');
            }
        }
        return sb.toString();
    }

    /**
     * 按预算输出一个槽位：**最新优先**保留（§7.5）——旧的先丢，
     * 与 P0-a 修过的 {@code clipContext} 同一取向（原先"最早优先"正是缺陷根因）。
     */
    private void appendSlot(StringBuilder sb, String title, Iterable<String> values, int budget) {
        List<String> items = new ArrayList<>();
        values.forEach(items::add);
        if (items.isEmpty()) {
            return;
        }

        List<String> kept = new ArrayList<>();
        int used = 0;
        for (int i = items.size() - 1; i >= 0; i--) {
            String item = items.get(i);
            if (item == null || item.isBlank()) {
                continue;
            }
            String candidate = item.strip();
            if (used + candidate.length() > budget) {
                if (kept.isEmpty()) {
                    // 单条就超预算：**必须截断**，否则"槽位预算"是假的
                    // （修订前整条塞进去——Memory 预算 600 也可能进 1500 字符）
                    kept.add(0, candidate.substring(0, Math.max(0, budget - 1)) + "…");
                }
                break;
            }
            kept.add(0, candidate);
            used += candidate.length();
        }
        if (kept.isEmpty()) {
            return;
        }

        sb.append("【").append(title).append("】\n");
        for (String item : kept) {
            sb.append(item.strip()).append('\n');
        }
    }

    /**
     * 反馈条目（P2.3 修订）：文案 + 它指向的缺失类型。
     *
     * <p>单独成槽而不是塞进 {@code missingEvidence} + {@code latestError}：
     * 那样同一条内容会在 prompt 里出现两遍，而且 {@code latestError} 逃出所有预算。
     */
    private record Feedback(String message, FeedbackClearKey clearKey) {
    }

    private record LatestError(AgentStepActionType actionType, String message) {
    }

    private final List<Feedback> feedbacks = new ArrayList<>();

    @Override
    public void noteFeedback(String message, FeedbackClearKey clearKey) {
        String text = oneLine(message);
        if (text.isBlank()) {
            return;
        }
        for (int i = 0; i < feedbacks.size(); i++) {
            Feedback existing = feedbacks.get(i);
            if (existing.message().equals(text)) {
                // 同文案只留一条；允许把类型从"不参与清除"补成具体类型
                if (existing.clearKey() == null && clearKey != null) {
                    feedbacks.set(i, new Feedback(text, clearKey));
                }
                return;
            }
        }
        feedbacks.add(new Feedback(text, clearKey));
    }

    /** 某类缺失被新证据补齐：清掉指向它的反馈（否则旧反馈会一直挂在上下文里自我强化） */
    private void clearFeedback(FeedbackClearKey clearKey) {
        feedbacks.removeIf(f -> f.clearKey() == clearKey);
    }

    private void clearLatestError(AgentStepActionType actionType) {
        if (latestError != null && latestError.actionType() == actionType) {
            latestError = null;
        }
    }

    @Override
    public boolean lastStepHadNewEvidence() {
        return this.lastStepHadNewEvidence;
    }

    /** 还没有任何可带去决策的事实（第 1 步之前，或所有动作都没产出结构化事实） */
    private boolean isEmpty() {
        return articleId == null && articleEvidence.isEmpty() && memories.isEmpty()
                && ragFacts.isEmpty() && completedActions.isEmpty() && latestError == null
                // 反馈也算事实，而且是最该优先看到的那种：只有一条"你被拒了、因为什么"时，
                // 判成空上下文等于让模型两眼一抹黑（这个漏洞是被 Runtime 上下文测试抓出来的）
                && feedbacks.isEmpty();
    }

    private boolean isVerificationEvidenceEmpty() {
        return articleId == null && articleEvidence.isEmpty() && memories.isEmpty()
                && ragFacts.isEmpty() && missingEvidence.isEmpty();
    }

    private String oneLine(String text) {
        return text == null ? "" : text.replaceAll("\\s+", " ").strip();
    }

    private String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private int asInt(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }
}
