package com.hailin.blogsystem.entity.dto;

import java.util.List;

public record ArticleActionBatch(
        Long articleId,
        List<ArticleInteractionAction> actions
) {
    public ArticleActionBatch {
        actions = List.copyOf(actions);
    }
}
