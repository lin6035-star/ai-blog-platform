package com.hailin.blogsystem.rag;

import java.util.List;

public record ArticleRagEvalCase(
        String id,
        String query,
        boolean shouldRouteToRag,
        List<Long> expectedArticleIds,
        String caseType,
        String notes
) {
}
