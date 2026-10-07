package com.hailin.blogsystem.ai.task.executor;

import com.hailin.blogsystem.ai.task.StepCode;
import com.hailin.blogsystem.ai.task.StepResult;
import com.hailin.blogsystem.ai.task.StepType;
import com.hailin.blogsystem.ai.task.TaskExecutionContext;
import com.hailin.blogsystem.ai.task.TaskStep;
import com.hailin.blogsystem.ai.task.TaskStepExecutor;
import com.hailin.blogsystem.ai.rag.ArticleRagSearchService;
import com.hailin.blogsystem.entity.dto.AiIntent;
import com.hailin.blogsystem.entity.dto.ArticleRagContext;
import com.hailin.blogsystem.entity.dto.ArticleRagSearchResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Task 文章检索执行器（第三刀）。
 *
 * <p>复用普通聊天的 RAG 混合召回、Rerank 与 Gate，但不调用回答模型：Task 仍以确定性文案汇总，
 * 同时与单目标文章搜索保持同一套相关性标准。
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class ArticleSearchExecutor implements TaskStepExecutor {

    /** 结果条数上限：汇总里列太多没有意义，总数会单独说明 */
    private static final int LIMIT = 5;
    /** 当前 Task run 可交给后续分析的正文片段上限；与 Rerank top-n 默认值一致。 */
    private static final int RAG_CONTEXT_LIMIT = 5;

    private final ArticleRagSearchService articleRagSearchService;

    @Override
    public StepType supports() {
        return StepType.ARTICLE_SEARCH;
    }

    @Override
    public StepResult execute(TaskExecutionContext context, TaskStep step) {
        String query = String.valueOf(step.input().getOrDefault("query", "")).trim();
        if (query.isBlank()) {
            return StepResult.failed(step.stepId(), StepCode.INTERNAL_ERROR, "没有给出要搜索的关键词");
        }

        try {
            AiIntent intent = new AiIntent();
            intent.setIntent("ARTICLE_SEARCH");
            intent.setKeyWord(query);
            ArticleRagSearchResult searchResult = articleRagSearchService.search(query, intent);
            List<ArticleRagContext> contexts = searchResult == null || searchResult.contexts() == null
                    ? List.of()
                    : searchResult.contexts();
            if (contexts.isEmpty()) {
                return StepResult.applied(step.stepId(),
                        "没有找到与「" + query + "」相关的文章",
                        Map.of("query", query, "total", 0L));
            }

            List<Map<String, Object>> ragEvidence = new ArrayList<>();
            for (ArticleRagContext ragContext : contexts) {
                if (ragContext == null || ragContext.articleId() == null
                        || ragContext.content() == null || ragContext.content().isBlank()) {
                    continue;
                }
                Map<String, Object> evidence = new LinkedHashMap<>();
                evidence.put("articleId", String.valueOf(ragContext.articleId()));
                evidence.put("title", ragContext.title() == null ? "" : ragContext.title());
                evidence.put("chunkIndex", ragContext.chunkIndex());
                evidence.put("content", ragContext.content());
                ragEvidence.add(evidence);
                if (ragEvidence.size() >= RAG_CONTEXT_LIMIT) {
                    break;
                }
            }

            Map<Long, ArticleRagContext> articles = new LinkedHashMap<>();
            for (ArticleRagContext ragContext : contexts) {
                if (ragContext == null || ragContext.articleId() == null) {
                    continue;
                }
                articles.putIfAbsent(ragContext.articleId(), ragContext);
                if (articles.size() >= LIMIT) {
                    break;
                }
            }

            List<String> titles = new ArrayList<>();
            List<Map<String, Object>> articleEvidence = new ArrayList<>();
            for (ArticleRagContext article : articles.values()) {
                String title = article.title() == null ? "" : article.title();
                titles.add(title);
                Map<String, Object> evidence = new LinkedHashMap<>();
                evidence.put("articleId", String.valueOf(article.articleId()));
                evidence.put("title", title);
                evidence.put("summary", article.content() == null ? "" : article.content());
                articleEvidence.add(evidence);
            }

            long total = articles.size();
            String summary = titles.isEmpty()
                    ? "没有找到与「" + query + "」相关的文章"
                    : "找到 " + total + " 篇与「" + query + "」相关的文章，例如：" + String.join("、", titles);

            Map<String, Object> output = new LinkedHashMap<>();
            output.put("query", query);
            output.put("total", total);
            output.put("titles", titles);
            output.put("articles", articleEvidence);
            output.put("ragContexts", ragEvidence);
            if (searchResult != null && searchResult.strategy() != null) {
                output.put("retrievalStrategy", searchResult.strategy().name());
            }

            return StepResult.applied(step.stepId(), summary, output);
        } catch (Exception e) {
            log.error("[TASK-EXEC] RAG 文章检索异常: stepId={}, query={}", step.stepId(), query, e);
            return StepResult.failed(step.stepId(), StepCode.INTERNAL_ERROR, "搜索失败，请稍后重试");
        }
    }
}
