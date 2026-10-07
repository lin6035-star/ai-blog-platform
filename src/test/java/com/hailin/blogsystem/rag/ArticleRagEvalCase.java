package com.hailin.blogsystem.rag;

import java.util.List;

/**
 * RAG 评测样例。
 *
 * <p>路由层有两个粒度：{@code shouldRouteToRag}（二值）与 {@code expectedRetrievalMode}
 * （{@code NONE} / {@code ARTICLE_SEARCH}，为将来的 {@code CURRENT_ARTICLE} 留位置）。
 * 两者表达同一件事，{@code ArticleRagEvalDatasetTests} 会校验它们一致，避免标注漂移。
 *
 * <p>{@code caseType} 决定这条样例考验什么：{@code TITLE_EXACT} 只验证"能召回"，
 * 真正的信息量在 {@code PARAPHRASE}（用户实际说法）与 {@code HARD_NEGATIVE}（阈值/Gate 能不能拒绝）。
 */
public record ArticleRagEvalCase(
        String id,
        String query,
        boolean shouldRouteToRag,
        String expectedRetrievalMode,
        List<Long> expectedArticleIds,
        String caseType,
        String notes
) {
}
