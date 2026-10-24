package com.hailin.blogsystem.ai.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hailin.blogsystem.entity.AiMessages;
import com.hailin.blogsystem.entity.dto.AgentStepActionType;
import com.hailin.blogsystem.mapper.AiAgentRunMapper;
import com.hailin.blogsystem.mapper.AiAgentStepMapper;
import com.hailin.blogsystem.service.AiConversationMemoryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

/**
 * 通用域 Agent Runtime（V3 通用思考模式）。
 *
 * 继承 AbstractAgentRuntime 公共循环骨架，本类只保留通用域差异：
 * - 白名单：QUERY_MEMORY / SEARCH_RAG + 通用终态（无 SUGGEST_WORKFLOW / SUGGEST_WRITE，
 *   通用域没有可建议的域内 Workflow，也不做写提案）
 * - maxSteps=3（比学习/文章域收紧，配合「能不查就不查」prompt 双闸门控延迟）
 * - 文案全部中性化（无领域语义）
 *
 * 入口：GENERAL_CHAT + needsThinking=true（分类器语义判定 → Planner 归一化 → 公共 SSE 管道）。
 */
@Service
@Slf4j
public class GeneralAgentRuntime extends AbstractAgentRuntime implements AgentRuntime {

    private static final Set<AgentStepActionType> ALLOWED_ACTIONS = Set.of(
            AgentStepActionType.QUERY_MEMORY,
            AgentStepActionType.SEARCH_RAG,
            AgentStepActionType.ASK_USER,
            AgentStepActionType.FINAL_ANSWER
    );

    private static final Set<String> ALLOWED_SUGGEST_WORKFLOW_TYPES = Set.of();

    /**
     * 「本会话最近对话」注入上限（2026-10-19）。
     *
     * <p>只给模型"会话里发生了什么"的摘要级信息：条数与每条字数都收紧，
     * 避免把通用 Agent 的决策 prompt 顶成聊天全文（它本来是为"能不查就不查"控延迟设计的）。
     */
    private static final int HISTORY_MAX_MESSAGES = 10;
    private static final int HISTORY_MESSAGE_MAX_CHARS = 120;

    private final AgentStepDecider decider;
    private final AiConversationMemoryService conversationMemoryService;

    public GeneralAgentRuntime(
            GeneralAgentStepDecider decider,
            GeneralAgentActionExecutor executor,
            AiAgentRunMapper runMapper,
            AiAgentStepMapper stepMapper,
            ObjectMapper objectMapper,
            AiConversationMemoryService conversationMemoryService
    ) {
        super(executor, runMapper, stepMapper, objectMapper);
        this.decider = decider;
        this.conversationMemoryService = conversationMemoryService;
    }

    // ==================== 领域钩子 ====================

    @Override
    protected AgentStepDecider decider() {
        return decider;
    }

    /**
     * 注入「本会话最近对话」（2026-10-19）。
     *
     * <p><b>它修的 bug</b>：通用 Agent 的只读白名单只有 QUERY_MEMORY / SEARCH_RAG，
     * 决策上下文里又没有会话历史——用户问「我们这个会话聊了什么」时，模型只能去查**长期记忆**，
     * 于是答出来的全是用户画像 / 偏好 / 项目状态（实测原话："它怎么好像完全不知道这个会话"）。
     *
     * <p>放这里而不是 {@code effectiveGoal}：goal 会落库并作为 QUERY_MEMORY 的检索原话，
     * 拼进历史会污染记忆召回（见 {@code AbstractAgentRuntime#additionalDecisionContext}）。
     *
     * <p>fail-open：读历史失败只告警，不影响本次回答（退化成改前的行为）。
     */
    @Override
    protected String additionalDecisionContext(String goal, Long userId, Long sessionId) {
        if (sessionId == null || userId == null) {
            return "";
        }
        List<AiMessages> recent;
        try {
            recent = conversationMemoryService.getRecentMessagesForUser(
                    sessionId, userId, HISTORY_MAX_MESSAGES);
        } catch (Exception e) {
            log.warn("会话历史读取失败（不影响决策）: sessionId={}", sessionId, e);
            return "";
        }
        if (recent == null || recent.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(
                "【本会话最近对话】用户问「这个会话聊了什么」时以这里为准；"
                        + "长期记忆是用户画像/偏好，不是会话内容。\n");
        for (AiMessages message : recent) {
            if (message == null || message.getContent() == null || message.getContent().isBlank()) {
                continue;
            }
            sb.append("user".equals(message.getRole()) ? "用户" : "AI")
                    .append("：")
                    .append(clipMessage(message.getContent()))
                    .append("\n");
        }
        return sb.toString().trim();
    }

    private static String clipMessage(String content) {
        String normalized = content.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= HISTORY_MESSAGE_MAX_CHARS) {
            return normalized;
        }
        return normalized.substring(0, HISTORY_MESSAGE_MAX_CHARS) + "…";
    }

    @Override
    protected Set<AgentStepActionType> allowedActions() {
        return ALLOWED_ACTIONS;
    }

    @Override
    protected Set<String> allowedSuggestWorkflowTypes() {
        return ALLOWED_SUGGEST_WORKFLOW_TYPES;
    }

    /** 通用域收紧决策步数（延迟控制：maxSteps 上限 + prompt 下限）。 */
    @Override
    protected int maxSteps() {
        return 3;
    }

    @Override
    protected String emptyAnswerFallback() {
        return "暂时无法回答你的问题，请补充一下背景或换个问法。";
    }

    @Override
    protected String emptyAskUserFallback() {
        return "你想了解或解决什么？";
    }

    @Override
    protected String defaultWorkflowSuggestionReason(String workflowType) {
        // 白名单无 SUGGEST_WORKFLOW，正常不会走到；保留中性实现兜底
        return "建议进入「" + workflowType + "」流程。";
    }

    @Override
    protected String suggestWorkflowRejectHint() {
        return "系统提示：你在没有任何查询结果时尝试建议启动 Workflow，后端已拒绝。";
    }

    @Override
    protected String emptySummaryFallback() {
        return "暂时无法整理回答，请补充一下具体问题。";
    }

    @Override
    protected String summaryHeader() {
        return "根据已有信息，整理如下：\n";
    }
}
