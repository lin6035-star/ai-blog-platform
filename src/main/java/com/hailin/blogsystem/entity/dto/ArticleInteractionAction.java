package com.hailin.blogsystem.entity.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;
import java.util.Optional;

@Getter
@RequiredArgsConstructor
public enum ArticleInteractionAction {
    LIKE_ARTICLE("likeArticle"),
    UNLIKE_ARTICLE("unlikeArticle"),
    FAVORITE_ARTICLE("favoriteArticle"),
    UNFAVORITE_ARTICLE("unfavoriteArticle"),
    FOLLOW_AUTHOR("followAuthor"),
    UNFOLLOW_AUTHOR("unfollowAuthor");

    private final String classifierValue;

    public static Optional<ArticleInteractionAction> fromClassifierValue(String value) {
        if (value == null) {
            return Optional.empty();
        }
        return Arrays.stream(values())
                .filter(action -> action.classifierValue.equals(value))
                .findFirst();
    }
}
