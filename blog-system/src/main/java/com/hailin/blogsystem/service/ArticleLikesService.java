package com.hailin.blogsystem.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hailin.blogsystem.entity.ArticleLikes;
import com.hailin.blogsystem.entity.dto.ArticleInteractionResult;

public interface ArticleLikesService extends IService<ArticleLikes> {
    void likeArticle(Long articleId);

    ArticleInteractionResult likeArticle(Long userId, Long articleId);

    void cancelLikeArticle(Long articleId);

    ArticleInteractionResult cancelLikeArticle(Long userId, Long articleId);
}
