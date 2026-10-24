package com.hailin.blogsystem.ai.agent;

import com.hailin.blogsystem.ai.memory.AiEpisodicMemoryRetrieveService;
import com.hailin.blogsystem.ai.memory.AiMemoryRetrieveService;
import com.hailin.blogsystem.ai.rag.ArticleRagSearchService;
import com.hailin.blogsystem.config.BlogAiProperties;
import com.hailin.blogsystem.entity.AiUserMemories;
import com.hailin.blogsystem.entity.dto.AiIntent;
import com.hailin.blogsystem.entity.dto.ArticleRagContext;
import com.hailin.blogsystem.entity.dto.ArticleRagSearchResult;
import com.hailin.blogsystem.entity.dto.EpisodicMemoryRagContext;
import com.hailin.blogsystem.entity.dto.MemoryRagContext;
import com.hailin.blogsystem.entity.dto.PageContextDTO;
import com.hailin.blogsystem.service.AiUserMemoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 通用域只读动作执行器（V3 通用思考模式）。
 *
 * - QUERY_MEMORY：语义记忆 + 情景记忆（与学习域同一链路，通用域默认检索词不同）
 * - SEARCH_RAG：站内文章知识（复用现有混合检索 + rerank）
 *
 * 观察输出为纯文本，便于裁剪后进入下一轮决策 prompt。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class GeneralAgentActionExecutorImpl implements GeneralAgentActionExecutor {

    private static final int TITLE_MAX = 40;
    private static final int TEXT_MAX = 200;

    private final AiMemoryRetrieveService aiMemoryRetrieveService;
    private final AiEpisodicMemoryRetrieveService aiEpisodicMemoryRetrieveService;
    private final ArticleRagSearchService articleRagSearchService;
    private final BlogAiProperties blogAiProperties;
    private final AiUserMemoryService aiUserMemoryService;

    @Override
    public AgentActionResult execute(AgentStepDecision decision, Long userId, PageContextDTO pageContext) {
        // P2.2：通用域尚未提供结构化事实（payload 为空），原样包装——对本域零行为变更
        return AgentActionResult.of(executeRaw(decision, userId, pageContext));
    }

    private String executeRaw(AgentStepDecision decision, Long userId, PageContextDTO pageContext) {
        if (userId == null) {
            return "当前未登录，无法查询你的记忆和上下文。";
        }
        return switch (decision.actionType()) {
            case QUERY_MEMORY -> queryMemory(decision, userId);
            case SEARCH_RAG -> searchRag(decision);
            case QUERY_LEARNING_DASHBOARD, QUERY_ARTICLE ->
                    throw new UnsupportedOperationException("领域动作不经过通用域执行器");
            case ASK_USER, FINAL_ANSWER, SUGGEST_WORKFLOW, SUGGEST_WRITE,
                 ARTICLE_INTERACTION_BATCH, TASK_STEP ->
                    throw new UnsupportedOperationException("终态动作不经过执行器");
        };
    }

    /**
     * 用户记忆检索：语义记忆 + 情景记忆，按检索词召回。
     */
    private String queryMemory(AgentStepDecision decision, Long userId) {
        // 检索词优先用 run 原始目标（Runtime 并入的 userGoal），其次 LLM 改写词：
        // 实测同一问题下原话 cosine=0.6241、改写 0.5607（详见 AgentStepDecision#memoryQuery）
        String query = decision.memoryQuery();
        if (query == null) {
            query = "用户情况";
        }

        StringBuilder sb = new StringBuilder("记忆摘要：\n");

        /*
         * 常驻记忆先注入：用户画像 / 偏好**不走向量相似度**。
         *
         * 2026-10-13 实测：记忆向量仍留在旧 embedding 空间，与当前 query 向量 cos≈0.0125（近似正交），
         * 纯向量召回恒空——"你说说你所了解的我"答"我还没有关于你的个人记忆记录"。
         * 常驻通道与相似度无关，是召回链路失准时的兜底，且与普通聊天路径（buildMemoryPrompt 的
         * listAlwaysPromptMemories）语义对齐。
         */
        try {
            List<AiUserMemories> alwaysMemories = aiUserMemoryService.listPromptMemories(userId);
            for (AiUserMemories memory : alwaysMemories) {
                sb.append("- 常驻记忆(").append(memory.getMemoryType()).append(")：")
                        .append(limit(memory.getContent(), TEXT_MAX)).append('\n');
            }
        } catch (Exception e) {
            log.warn("Agent 常驻记忆读取失败，userId={}", userId, e);
        }

        try {
            List<MemoryRagContext> memories = aiMemoryRetrieveService.retrieve(userId, query);
            for (MemoryRagContext memory : memories) {
                sb.append("- 语义记忆(").append(memory.memoryType()).append(")：")
                        .append(limit(memory.content(), TEXT_MAX)).append('\n');
            }
        } catch (Exception e) {
            log.warn("Agent 语义记忆检索失败，userId={}", userId, e);
            sb.append("- 语义记忆检索失败\n");
        }

        try {
            List<EpisodicMemoryRagContext> memories = aiEpisodicMemoryRetrieveService.retrieveForPrompt(
                    userId, blogAiProperties.getProjectKey(), query
            );
            for (EpisodicMemoryRagContext memory : memories) {
                sb.append("- 情景记忆(").append(memory.memoryType()).append(")：")
                        .append(limit(memory.content(), TEXT_MAX)).append('\n');
            }
        } catch (Exception e) {
            log.warn("Agent 情景记忆检索失败，userId={}", userId, e);
            sb.append("- 情景记忆检索失败\n");
        }

        return sb.toString();
    }

    /**
     * 站内文章知识检索（复用现有混合检索 + rerank 链路）。
     */
    private String searchRag(AgentStepDecision decision) {
        String keyword = text(decision.input(), "keyword");
        if (keyword == null || keyword.isBlank()) {
            return "站内检索缺少关键词，无法检索。";
        }

        AiIntent intent = new AiIntent();
        intent.setIntent("ARTICLE_SEARCH");
        intent.setKeyWord(keyword);

        ArticleRagSearchResult result = articleRagSearchService.search(keyword, intent);
        if (result.contexts() == null || result.contexts().isEmpty()) {
            return "站内没有检索到与「" + keyword + "」相关的文章知识。";
        }

        StringBuilder sb = new StringBuilder("站内文章知识检索结果（")
                .append(result.strategy()).append("）：\n");
        int index = 1;
        for (ArticleRagContext context : result.contexts()) {
            sb.append(index++).append(". 《").append(limit(context.title(), TITLE_MAX))
                    .append("》(").append(context.articleId()).append(")：")
                    .append(limit(context.content(), TEXT_MAX)).append('\n');
        }
        return sb.toString();
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
