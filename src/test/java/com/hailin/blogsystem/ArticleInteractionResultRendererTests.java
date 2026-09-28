package com.hailin.blogsystem;

import com.hailin.blogsystem.ai.agent.ArticleInteractionResultRenderer;
import com.hailin.blogsystem.entity.dto.ArticleInteractionAction;
import com.hailin.blogsystem.entity.dto.ArticleInteractionResult;
import com.hailin.blogsystem.entity.dto.ArticleInteractionResultCode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ArticleInteractionResultRendererTests {

    private final ArticleInteractionResultRenderer renderer = new ArticleInteractionResultRenderer();

    @Test
    void rendersFullSuccessOnlyFromStructuredResults() {
        String message = renderer.render(
                List.of(ArticleInteractionAction.LIKE_ARTICLE, ArticleInteractionAction.FAVORITE_ARTICLE),
                List.of(
                        success(ArticleInteractionAction.LIKE_ARTICLE, "点赞成功"),
                        success(ArticleInteractionAction.FAVORITE_ARTICLE, "收藏成功")
                ));

        assertThat(message).isEqualTo("已完成：点赞成功；收藏成功。");
    }

    @Test
    void rendersPartialSuccessWithoutClaimingEverythingSucceeded() {
        String message = renderer.render(
                List.of(ArticleInteractionAction.LIKE_ARTICLE, ArticleInteractionAction.FOLLOW_AUTHOR),
                List.of(
                        success(ArticleInteractionAction.LIKE_ARTICLE, "点赞成功"),
                        failure(ArticleInteractionAction.FOLLOW_AUTHOR, "不能关注自己")
                ));

        assertThat(message).isEqualTo("部分完成：点赞成功。未完成：不能关注自己。");
    }

    @Test
    void rendersFullFailure() {
        String message = renderer.render(
                List.of(ArticleInteractionAction.LIKE_ARTICLE, ArticleInteractionAction.FAVORITE_ARTICLE),
                List.of(
                        failure(ArticleInteractionAction.LIKE_ARTICLE, "文章不存在"),
                        failure(ArticleInteractionAction.FAVORITE_ARTICLE, "文章不存在")
                ));

        assertThat(message).isEqualTo("未完成：文章不存在；文章不存在。");
    }

    @Test
    void rejectsMissingOrDuplicateResults() {
        List<ArticleInteractionAction> requested = List.of(
                ArticleInteractionAction.LIKE_ARTICLE,
                ArticleInteractionAction.FAVORITE_ARTICLE);

        assertThatThrownBy(() -> renderer.render(
                requested,
                List.of(success(ArticleInteractionAction.LIKE_ARTICLE, "点赞成功"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("完整");

        assertThatThrownBy(() -> renderer.render(
                requested,
                List.of(
                        success(ArticleInteractionAction.LIKE_ARTICLE, "点赞成功"),
                        success(ArticleInteractionAction.LIKE_ARTICLE, "已经点过赞")
                )))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("重复");
    }

    private ArticleInteractionResult success(ArticleInteractionAction action, String summary) {
        return ArticleInteractionResult.success(
                action,
                ArticleInteractionResultCode.APPLIED,
                summary,
                false,
                true);
    }

    private ArticleInteractionResult failure(ArticleInteractionAction action, String summary) {
        return ArticleInteractionResult.failure(
                action,
                ArticleInteractionResultCode.FORBIDDEN,
                summary,
                false,
                false);
    }
}
