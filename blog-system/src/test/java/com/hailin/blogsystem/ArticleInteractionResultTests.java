package com.hailin.blogsystem;

import com.hailin.blogsystem.entity.dto.ArticleInteractionAction;
import com.hailin.blogsystem.entity.dto.ArticleInteractionOutcome;
import com.hailin.blogsystem.entity.dto.ArticleInteractionResult;
import com.hailin.blogsystem.entity.dto.ArticleInteractionResultCode;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class ArticleInteractionResultTests {

    @Test
    void exposesExactlyTheSixServerExecutedActionsWithClassifierValues() {
        assertThat(Arrays.stream(ArticleInteractionAction.values())
                .map(ArticleInteractionAction::getClassifierValue))
                .containsExactly(
                        "likeArticle",
                        "unlikeArticle",
                        "favoriteArticle",
                        "unfavoriteArticle",
                        "followAuthor",
                        "unfollowAuthor"
                );

        assertThat(ArticleInteractionAction.fromClassifierValue("favoriteArticle"))
                .contains(ArticleInteractionAction.FAVORITE_ARTICLE);
        assertThat(ArticleInteractionAction.fromClassifierValue("scrollToComments"))
                .isEmpty();
    }

    @Test
    void appliedAndAlreadySatisfiedAreSuccessfulFacts() {
        ArticleInteractionResult applied = ArticleInteractionResult.success(
                ArticleInteractionAction.LIKE_ARTICLE,
                ArticleInteractionResultCode.APPLIED,
                "已点赞",
                false,
                true
        );
        ArticleInteractionResult already = ArticleInteractionResult.success(
                ArticleInteractionAction.LIKE_ARTICLE,
                ArticleInteractionResultCode.ALREADY_SATISFIED,
                "已经点过赞",
                true,
                true
        );

        assertThat(applied.outcome()).isEqualTo(ArticleInteractionOutcome.SUCCESS);
        assertThat(already.outcome()).isEqualTo(ArticleInteractionOutcome.SUCCESS);
        assertThat(applied.after()).isTrue();
        assertThat(already.before()).isTrue();
    }

    @Test
    void failureCarriesStableCodeInsteadOfRequiringMessageParsing() {
        ArticleInteractionResult result = ArticleInteractionResult.failure(
                ArticleInteractionAction.FOLLOW_AUTHOR,
                ArticleInteractionResultCode.FORBIDDEN,
                "不能关注自己",
                false,
                false
        );

        assertThat(result.outcome()).isEqualTo(ArticleInteractionOutcome.FAILED);
        assertThat(result.resultCode()).isEqualTo(ArticleInteractionResultCode.FORBIDDEN);
        assertThat(result.summary()).isEqualTo("不能关注自己");
    }
}
