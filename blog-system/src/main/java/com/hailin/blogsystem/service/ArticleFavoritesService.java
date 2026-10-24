package com.hailin.blogsystem.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hailin.blogsystem.entity.ArticleFavorites;
import com.hailin.blogsystem.entity.dto.ArticleInteractionResult;

public interface ArticleFavoritesService extends IService<ArticleFavorites> {
    void favoriteArticle(Long articleId);

    ArticleInteractionResult favoriteArticle(Long userId, Long articleId);

    void cancelFavorite(Long articleId);

    ArticleInteractionResult cancelFavorite(Long userId, Long articleId);
}
