package com.hailin.blogsystem.ai.task;

import com.hailin.blogsystem.entity.dto.ArticleRagContext;
import com.hailin.blogsystem.entity.vo.ArticleRagReferenceVO;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 将 Task 搜索步骤的结构化文章证据转换为聊天已有的引用卡片协议。 */
public final class TaskArticleReferenceSupport {

    private TaskArticleReferenceSupport() {
    }

    public static List<ArticleRagReferenceVO> fromResults(List<StepResult> results) {
        if (results == null || results.isEmpty()) {
            return List.of();
        }

        Map<Long, ArticleRagReferenceVO> references = new LinkedHashMap<>();
        for (StepResult result : results) {
            if (result == null || result.output() == null) {
                continue;
            }
            Object articles = result.output().get("articles");
            if (!(articles instanceof List<?> rows)) {
                continue;
            }
            for (Object row : rows) {
                if (!(row instanceof Map<?, ?> article)) {
                    continue;
                }
                Long articleId = parseArticleId(article.get("articleId"));
                if (articleId == null || references.containsKey(articleId)) {
                    continue;
                }
                ArticleRagContext context = new ArticleRagContext(
                        articleId,
                        text(article.get("title")),
                        null,
                        text(article.get("summary"))
                );
                references.put(articleId, ArticleRagReferenceVO.from(context));
            }
        }
        return List.copyOf(references.values());
    }

    private static Long parseArticleId(Object value) {
        if (value instanceof Number number) {
            long id = number.longValue();
            return id > 0 ? id : null;
        }
        if (value == null) {
            return null;
        }
        try {
            long id = Long.parseLong(String.valueOf(value).trim());
            return id > 0 ? id : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
