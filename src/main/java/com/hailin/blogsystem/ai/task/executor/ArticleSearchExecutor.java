package com.hailin.blogsystem.ai.task.executor;

import com.hailin.blogsystem.ai.task.StepCode;
import com.hailin.blogsystem.ai.task.StepResult;
import com.hailin.blogsystem.ai.task.StepType;
import com.hailin.blogsystem.ai.task.TaskExecutionContext;
import com.hailin.blogsystem.ai.task.TaskStep;
import com.hailin.blogsystem.ai.task.TaskStepExecutor;
import com.hailin.blogsystem.entity.Articles;
import com.hailin.blogsystem.search.ArticleSearchService;
import com.hailin.blogsystem.service.ArticlesService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 站内文章检索执行器（第三刀）。
 *
 * <p>用的是**站内搜索**（ES 全文检索、IK 分词），不是给 AI 用的 RAG 检索——
 * 用户说"帮我找找还有没有关于 Redis 的文章"要的是站内结果，不是知识片段。
 *
 * <p>{@code search()} 返回的是 articleIds（不是标题），所以这里再查一次拿标题——
 * 汇总文案要给用户看的是"找到了哪几篇"，而不是一串 ID。
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class ArticleSearchExecutor implements TaskStepExecutor {

    /** 结果条数上限：汇总里列太多没有意义，总数会单独说明 */
    private static final int LIMIT = 5;

    private final ArticleSearchService articleSearchService;
    private final ArticlesService articlesService;

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
            // sort/categoryId 传 null 走默认相关性排序——与站内搜索页的默认行为一致
            ArticleSearchService.SearchHits hits =
                    articleSearchService.search(query, null, null, 1, LIMIT);
            if (hits == null || hits.articleIds().isEmpty()) {
                return StepResult.applied(step.stepId(),
                        "没有找到与「" + query + "」相关的文章",
                        Map.of("query", query, "total", 0L));
            }

            List<Articles> articles = articlesService.listByIds(hits.articleIds());
            List<String> titles = new ArrayList<>();
            List<Map<String, Object>> articleEvidence = new ArrayList<>();
            if (articles != null) {
                // listByIds 不保证顺序，按命中的 id 顺序还原，避免展示顺序随机
                Map<Long, Articles> byId = new LinkedHashMap<>();
                articles.forEach(a -> byId.put(a.getId(), a));
                for (Long id : hits.articleIds()) {
                    Articles a = byId.get(id);
                    if (a != null) {
                        titles.add(a.getTitle());
                        Map<String, Object> evidence = new LinkedHashMap<>();
                        evidence.put("articleId", String.valueOf(a.getId()));
                        evidence.put("title", a.getTitle() == null ? "" : a.getTitle());
                        evidence.put("summary", a.getSummary() == null ? "" : a.getSummary());
                        articleEvidence.add(evidence);
                    }
                }
            }

            String summary = titles.isEmpty()
                    ? "找到了 " + hits.total() + " 篇与「" + query + "」相关的文章"
                    : "找到 " + hits.total() + " 篇与「" + query + "」相关的文章，例如：" + String.join("、", titles);

            Map<String, Object> output = new LinkedHashMap<>();
            output.put("query", query);
            output.put("total", hits.total());
            output.put("titles", titles);
            output.put("articles", articleEvidence);

            return StepResult.applied(step.stepId(), summary, output);
        } catch (Exception e) {
            log.error("[TASK-EXEC] 站内检索异常: stepId={}, query={}", step.stepId(), query, e);
            return StepResult.failed(step.stepId(), StepCode.INTERNAL_ERROR, "搜索失败，请稍后重试");
        }
    }
}
