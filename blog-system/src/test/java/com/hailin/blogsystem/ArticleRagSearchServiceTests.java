package com.hailin.blogsystem;

import com.hailin.blogsystem.ai.rag.ArticleRagKeywordRetriever;
import com.hailin.blogsystem.ai.rag.ArticleRagRanker;
import com.hailin.blogsystem.ai.rag.ArticleRagRankResult;
import com.hailin.blogsystem.ai.rag.ArticleRagRelevanceGate;
import com.hailin.blogsystem.ai.rag.ArticleRagRetrieveService;
import com.hailin.blogsystem.ai.rag.ArticleRagSearchService;
import com.hailin.blogsystem.ai.rag.ArticleRagTraceRecorder;
import com.hailin.blogsystem.entity.dto.AiIntent;
import com.hailin.blogsystem.entity.dto.ArticleRagContext;
import com.hailin.blogsystem.entity.dto.ArticleRagSearchResult;
import com.hailin.blogsystem.entity.dto.ArticleRagSearchStrategy;
import com.hailin.blogsystem.service.ArticlesService;
import com.hailin.blogsystem.config.BlogAiProperties;
import com.hailin.blogsystem.entity.vo.PageVO;
import com.hailin.blogsystem.entity.Articles;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ArticleRagSearchServiceTests {

    private final ArticleRagRetrieveService retrieveService = mock(ArticleRagRetrieveService.class);
    private final ArticleRagRanker ranker = mock(ArticleRagRanker.class);
    private final ArticlesService articlesService = mock(ArticlesService.class);
    private final ArticleRagKeywordRetriever keywordRetriever = mock(ArticleRagKeywordRetriever.class);
    private final ArticleRagTraceRecorder traceRecorder = mock(ArticleRagTraceRecorder.class);
    private final ArticleRagRelevanceGate relevanceGate = new ArticleRagRelevanceGate(new BlogAiProperties());
    private final ArticleRagSearchService searchService = new ArticleRagSearchService(
            retrieveService,
            ranker,
            articlesService,
            keywordRetriever,
            traceRecorder,
            relevanceGate
    );

    @Test
    void skipsArticleRagForNonArticleSearchIntent() {
        AiIntent intent = new AiIntent();
        intent.setIntent("GENERAL_CHAT");

        ArticleRagSearchResult result = searchService.search("你好，介绍一下你自己", intent);

        assertThat(result.strategy()).isEqualTo(ArticleRagSearchStrategy.EMPTY);
        assertThat(result.contexts()).isEmpty();
        verify(retrieveService, never()).retrieve(anyString());
        verify(keywordRetriever, never()).retrieve(anyString());
        verify(ranker, never()).rank(anyString(), any());
        verify(articlesService, never()).getArticles(any(), any(), any(), any(), any());
    }

    @Test
    void usesHybridRetrievalForArticleSearchIntent() {
        AiIntent intent = new AiIntent();
        intent.setIntent("ARTICLE_SEARCH");
        intent.setKeyWord("Redis");

        List<ArticleRagContext> vectorContexts = List.of(
                new ArticleRagContext(1L, "Redis 实战", 0, "Redis 可以用于缓存。")
        );
        List<ArticleRagContext> keywordContexts = List.of(
                new ArticleRagContext(2L, "Redis 秒杀", 0, "Redis 可以用于秒杀库存扣减。")
        );
        List<ArticleRagContext> rankedContexts = List.of(vectorContexts.get(0), keywordContexts.get(0));

        when(retrieveService.retrieve("有没有关于 Redis 的文章")).thenReturn(vectorContexts);
        when(keywordRetriever.retrieve("Redis")).thenReturn(keywordContexts);
        /*
         * ⚠️ rerank 收到的是**完整问题**，不是削过的关键词。
         * 这一条曾经是 "Redis"——因为那时 rankQuestion 取 lexicalQuery（停用词表削出来的），
         * 结果是"缓存"这类两字 query 让所有候选分数被摊平，Gate 只放得过 top1。
         * 现在锁的就是"用完整问题打分"这个契约。
         */
        when(ranker.rankWithEvidence("有没有关于 Redis 的文章", rankedContexts))
                .thenReturn(modelResult(rankedContexts, 0.90D));
        when(articlesService.listByIds(List.of(1L, 2L))).thenReturn(publishedArticles(1L, 2L));

        ArticleRagSearchResult result = searchService.search("有没有关于 Redis 的文章", intent);

        assertThat(result.strategy()).isEqualTo(ArticleRagSearchStrategy.HYBRID);
        assertThat(result.contexts()).containsExactlyElementsOf(rankedContexts);
        verify(retrieveService).retrieve("有没有关于 Redis 的文章");
        verify(keywordRetriever).retrieve("Redis");
        verify(ranker).rankWithEvidence("有没有关于 Redis 的文章", rankedContexts);
    }

    @Test
    void retriesKeywordRetrievalWhenAndQueryHasNoHits() {
        AiIntent intent = new AiIntent();
        intent.setIntent("ARTICLE_SEARCH");
        intent.setKeyWord("Redis 缓存");

        List<ArticleRagContext> fallbackContexts = List.of(
                new ArticleRagContext(2L, "Redis 缓存", 0, "缓存读写一致性。")
        );

        when(retrieveService.retrieve("有没有关于 Redis 缓存 的文章")).thenReturn(List.of());
        when(keywordRetriever.retrieve("Redis 缓存")).thenReturn(List.of());
        when(keywordRetriever.retrieveFallback("Redis 缓存")).thenReturn(fallbackContexts);
        // rerank 两轮（首轮空 / 兜底轮）拿到的都是**完整问题**，不是关键词
        when(ranker.rankWithEvidence("有没有关于 Redis 缓存 的文章", List.of()))
                .thenReturn(ArticleRagRankResult.empty());
        when(ranker.rankWithEvidence("有没有关于 Redis 缓存 的文章", fallbackContexts))
                .thenReturn(modelResult(fallbackContexts, 0.90D));
        when(articlesService.listByIds(List.of(2L))).thenReturn(publishedArticles(2L));

        ArticleRagSearchResult result = searchService.search("有没有关于 Redis 缓存 的文章", intent);

        assertThat(result.strategy()).isEqualTo(ArticleRagSearchStrategy.HYBRID);
        assertThat(result.contexts()).containsExactlyElementsOf(fallbackContexts);
        verify(keywordRetriever).retrieve("Redis 缓存");
        verify(keywordRetriever).retrieveFallback("Redis 缓存");
        verify(ranker).rankWithEvidence("有没有关于 Redis 缓存 的文章", fallbackContexts);
    }

    @Test
    void rejectsLowRerankCandidatesInsteadOfTreatingAnyCandidateAsRelevant() {
        AiIntent intent = new AiIntent();
        intent.setIntent("ARTICLE_SEARCH");
        intent.setKeyWord("Redis MySQL 剪辑视频");
        ArticleRagContext unrelated = new ArticleRagContext(1L, "Redis 缓存", 0, "缓存一致性");

        when(retrieveService.retrieve("Redis 和 MySQL 哪个适合剪辑视频")).thenReturn(List.of(unrelated));
        when(keywordRetriever.retrieve("Redis MySQL 剪辑视频")).thenReturn(List.of(unrelated));
        when(ranker.rankWithEvidence("Redis 和 MySQL 哪个适合剪辑视频", List.of(unrelated)))
                .thenReturn(modelResult(List.of(unrelated), 0.72D));
        when(articlesService.getArticles(any(), any(), any(), any(), any()))
                .thenReturn(new PageVO<>(List.of(), 0L, 1L, 3L));

        ArticleRagSearchResult result = searchService.search("Redis 和 MySQL 哪个适合剪辑视频", intent);

        assertThat(result.strategy()).isEqualTo(ArticleRagSearchStrategy.EMPTY);
        assertThat(result.contexts()).isEmpty();
    }

    @Test
    void removesCandidatesThatAreNoLongerPublishedInMysql() {
        AiIntent intent = new AiIntent();
        intent.setIntent("ARTICLE_SEARCH");
        intent.setKeyWord("Redis");
        ArticleRagContext stale = new ArticleRagContext(1L, "Redis 缓存", 0, "缓存一致性");

        when(retrieveService.retrieve("Redis")).thenReturn(List.of(stale));
        when(keywordRetriever.retrieve("Redis")).thenReturn(List.of(stale));
        when(ranker.rankWithEvidence("Redis", List.of(stale)))
                .thenReturn(modelResult(List.of(stale), 0.95D));
        when(articlesService.listByIds(List.of(1L))).thenReturn(List.of());

        ArticleRagSearchResult result = searchService.search("Redis", intent);

        assertThat(result.strategy()).isEqualTo(ArticleRagSearchStrategy.EMPTY);
        assertThat(result.contexts()).isEmpty();
    }

    private ArticleRagRankResult modelResult(List<ArticleRagContext> contexts, double score) {
        return new ArticleRagRankResult(
                ArticleRagRankResult.Status.MODEL_APPLIED,
                java.util.stream.IntStream.range(0, contexts.size())
                        .mapToObj(index -> new ArticleRagRankResult.Candidate(
                                contexts.get(index), score, index + 1
                        ))
                        .toList()
        );
    }

    private List<Articles> publishedArticles(Long... ids) {
        return java.util.Arrays.stream(ids).map(id -> {
            Articles article = new Articles();
            article.setId(id);
            article.setStatus(1);
            return article;
        }).toList();
    }
}
