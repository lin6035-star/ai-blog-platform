package com.hailin.blogsystem.entity.dto;

public record ArticleInteractionResult(
        ArticleInteractionAction actionType,
        ArticleInteractionOutcome outcome,
        ArticleInteractionResultCode resultCode,
        String summary,
        Boolean before,
        Boolean after
) {
    public static ArticleInteractionResult success(
            ArticleInteractionAction actionType,
            ArticleInteractionResultCode resultCode,
            String summary,
            Boolean before,
            Boolean after
    ) {
        if (resultCode != ArticleInteractionResultCode.APPLIED
                && resultCode != ArticleInteractionResultCode.ALREADY_SATISFIED) {
            throw new IllegalArgumentException("成功结果只允许 APPLIED 或 ALREADY_SATISFIED");
        }
        return new ArticleInteractionResult(
                actionType,
                ArticleInteractionOutcome.SUCCESS,
                resultCode,
                summary,
                before,
                after
        );
    }

    public static ArticleInteractionResult failure(
            ArticleInteractionAction actionType,
            ArticleInteractionResultCode resultCode,
            String summary,
            Boolean before,
            Boolean after
    ) {
        if (resultCode == ArticleInteractionResultCode.APPLIED
                || resultCode == ArticleInteractionResultCode.ALREADY_SATISFIED) {
            throw new IllegalArgumentException("失败结果不能使用成功 resultCode");
        }
        return new ArticleInteractionResult(
                actionType,
                ArticleInteractionOutcome.FAILED,
                resultCode,
                summary,
                before,
                after
        );
    }
}
