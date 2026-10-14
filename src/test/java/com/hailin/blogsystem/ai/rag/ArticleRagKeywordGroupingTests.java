package com.hailin.blogsystem.ai.rag;

import com.hailin.blogsystem.config.BlogAiProperties;
import com.hailin.blogsystem.entity.dto.ArticleRagContext;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * keyword 检索的「按文章摊开」逻辑（背景见 {@link ArticleRagKeywordRetriever} 的类注释）。
 *
 * <p>放在 {@code ai.rag} 包下是为了直接调包级可见的 {@code pickByArticle}
 * ——它是纯函数，不需要起 ES。（同包测试的另一个先例：{@code ArticleRagRetryPolicyTests}。）
 *
 * <p><b>为什么必须有这组用例</b>：这个 bug 的表现是"AI 只找到 1 篇、而且没讲全"，
 * 它<b>不会让任何现有测试变红</b>——检索依然"成功返回了结果"，只是覆盖的文章太少。
 * 实测那次：ES 里 51 个 chunk / 12 篇命中 Java，链路上却只让 1 篇进了 prompt。
 *
 * <p>被替掉的旧行为是"取分数最高的 N 个 chunk"。而 chunk 级的分数分布天然扎堆：
 * 同一篇文章的多个片段分数接近、会连续排在前面——所以"取前 N 个"经常只覆盖一两篇。
 */
class ArticleRagKeywordGroupingTests {

    private static BlogAiProperties.Keyword config(int maxArticles, int chunksPerArticle) {
        BlogAiProperties.Keyword keyword = new BlogAiProperties.Keyword();
        keyword.setMaxArticles(maxArticles);
        keyword.setChunksPerArticle(chunksPerArticle);
        return keyword;
    }

    private static ArticleRagContext chunk(long articleId, int chunkIndex) {
        return new ArticleRagContext(articleId, "title-" + articleId, chunkIndex, "content-" + chunkIndex);
    }

    @Test
    void spreadsHitsAcrossArticlesInsteadOfTakingTopChunks() {
        // 模拟真实分布：第 1 篇霸占了前 5 个 chunk，其它文章都排在后面
        List<ArticleRagContext> hits = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            hits.add(chunk(1L, i));
        }
        hits.add(chunk(2L, 0));
        hits.add(chunk(3L, 0));
        hits.add(chunk(4L, 0));

        List<ArticleRagContext> picked = ArticleRagKeywordRetriever.pickByArticle(hits, config(8, 2));

        // 关键断言：结果里必须出现 4 篇，而不是"前 5 条全来自第 1 篇"
        assertThat(picked).extracting(ArticleRagContext::articleId)
                .containsExactly(1L, 1L, 2L, 3L, 4L);
    }

    @Test
    void keepsAtMostChunksPerArticle() {
        List<ArticleRagContext> hits = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            hits.add(chunk(7L, i));
        }

        List<ArticleRagContext> picked = ArticleRagKeywordRetriever.pickByArticle(hits, config(8, 2));

        // 单篇 10 个片段只留 2 个——省下的额度留给别的文章
        assertThat(picked).hasSize(2);
        assertThat(picked).extracting(ArticleRagContext::chunkIndex).containsExactly(0, 1);
    }

    @Test
    void stopsAdmittingNewArticlesOnceMaxArticlesIsReached() {
        List<ArticleRagContext> hits = new ArrayList<>();
        for (long id = 1; id <= 6; id++) {
            hits.add(chunk(id, 0));
        }

        List<ArticleRagContext> picked = ArticleRagKeywordRetriever.pickByArticle(hits, config(3, 2));

        assertThat(picked).extracting(ArticleRagContext::articleId).containsExactly(1L, 2L, 3L);
    }

    @Test
    void stillFillsQuotaForAlreadyPickedArticlesAfterTheCap() {
        // 篇数上限 2：前两篇各占 1 个名额后，第 1 篇的第二个片段仍然该被收下
        List<ArticleRagContext> hits = List.of(
                chunk(1L, 0),
                chunk(2L, 0),
                chunk(1L, 1),
                chunk(3L, 0));

        List<ArticleRagContext> picked = ArticleRagKeywordRetriever.pickByArticle(hits, config(2, 2));

        // 第 3 篇被上限挡住，但它不该把第 1 篇的第二个片段也一起挤掉
        assertThat(picked).extracting(ArticleRagContext::articleId).containsExactly(1L, 2L, 1L);
    }

    @Test
    void skipsHitsWithoutArticleId() {
        List<ArticleRagContext> hits = List.of(
                new ArticleRagContext(null, "no-id", 0, "content"),
                chunk(1L, 0));

        List<ArticleRagContext> picked = ArticleRagKeywordRetriever.pickByArticle(hits, config(8, 2));

        // 归不了组的片段没法参与"覆盖 N 篇"，直接跳过
        assertThat(picked).extracting(ArticleRagContext::articleId).containsExactly(1L);
    }

    @Test
    void toleratesEmptyAndNullInput() {
        assertThat(ArticleRagKeywordRetriever.pickByArticle(List.of(), config(8, 2))).isEmpty();
        assertThat(ArticleRagKeywordRetriever.pickByArticle(null, config(8, 2))).isEmpty();
    }

    @Test
    void clampsNonPositiveConfigToAtLeastOne() {
        // 配置写成 0 不该让检索直接返回空——那样表现成"RAG 坏了"，很难查
        List<ArticleRagContext> hits = List.of(chunk(1L, 0), chunk(2L, 0), chunk(1L, 1));

        List<ArticleRagContext> picked = ArticleRagKeywordRetriever.pickByArticle(hits, config(0, 0));

        assertThat(picked).extracting(ArticleRagContext::articleId).containsExactly(1L);
    }
}
