package com.hailin.blogsystem;

import com.hailin.blogsystem.ai.rag.ArticleRagKeywordRetriever;
import com.hailin.blogsystem.ai.rag.ArticleRagRanker;
import com.hailin.blogsystem.ai.rag.ArticleRagRankResult;
import com.hailin.blogsystem.ai.rag.ArticleRagRelevanceGate;
import com.hailin.blogsystem.ai.rag.ArticleRagRetrievalTrace;
import com.hailin.blogsystem.ai.rag.ArticleRagRetrieveService;
import com.hailin.blogsystem.ai.rag.ArticleRagSearchService;
import com.hailin.blogsystem.ai.rag.ArticleRagTraceRecorder;
import com.hailin.blogsystem.entity.dto.AiIntent;
import com.hailin.blogsystem.entity.dto.ArticleRagContext;
import com.hailin.blogsystem.entity.dto.ArticleRagSearchStrategy;
import com.hailin.blogsystem.service.ArticlesService;
import com.hailin.blogsystem.config.BlogAiProperties;
import com.hailin.blogsystem.entity.Articles;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;

class ArticleRagRetrievalTraceTests {

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
    void recordsEveryRetrievalStageWithoutChangingTheSearchResult() {
        AiIntent intent = new AiIntent();
        intent.setIntent("ARTICLE_SEARCH");
        intent.setKeyWord("Redis 缓存");

        ArticleRagContext vector = new ArticleRagContext(1L, "Redis 实战", 0, "缓存实践");
        ArticleRagContext keyword = new ArticleRagContext(2L, "缓存穿透", 1, "布隆过滤器");
        List<ArticleRagContext> merged = List.of(vector, keyword);
        List<ArticleRagContext> ranked = List.of(keyword, vector);

        when(retrieveService.retrieve("有没有关于 Redis 缓存的文章")).thenReturn(List.of(vector));
        when(keywordRetriever.retrieve("Redis 缓存")).thenReturn(List.of(keyword));
        // rerank 收到的是**完整问题**，不是削出来的关键词（见 ArticleRagSearchService 的说明）
        when(ranker.rankWithEvidence("有没有关于 Redis 缓存的文章", merged))
                .thenReturn(modelResult(ranked, 0.90D));
        when(articlesService.listByIds(List.of(2L, 1L))).thenReturn(publishedArticles(2L, 1L));

        var result = searchService.search("有没有关于 Redis 缓存的文章", intent);

        assertThat(result.strategy()).isEqualTo(ArticleRagSearchStrategy.HYBRID);
        assertThat(result.contexts()).containsExactlyElementsOf(ranked);

        ArgumentCaptor<ArticleRagRetrievalTrace> captor =
                ArgumentCaptor.forClass(ArticleRagRetrievalTrace.class);
        verify(traceRecorder).record(captor.capture());

        ArticleRagRetrievalTrace trace = captor.getValue();
        assertThat(trace.question()).isEqualTo("有没有关于 Redis 缓存的文章");
        assertThat(trace.intent()).isEqualTo("ARTICLE_SEARCH");
        assertThat(trace.lexicalQuery()).isEqualTo("Redis 缓存");
        assertThat(trace.routed()).isTrue();
        assertThat(trace.skipReason()).isNull();
        assertThat(trace.vectorCandidates()).extracting(ArticleRagRetrievalTrace.Candidate::articleId)
                .containsExactly(1L);
        assertThat(trace.keywordCandidates()).extracting(ArticleRagRetrievalTrace.Candidate::articleId)
                .containsExactly(2L);
        assertThat(trace.mergedCandidates()).extracting(ArticleRagRetrievalTrace.Candidate::articleId)
                .containsExactly(1L, 2L);
        assertThat(trace.rankedCandidates()).extracting(ArticleRagRetrievalTrace.Candidate::articleId)
                .containsExactly(2L, 1L);
        assertThat(trace.strategy()).isEqualTo(ArticleRagSearchStrategy.HYBRID);
        assertThat(trace.gateApplied()).isTrue();
        assertThat(trace.vectorElapsedMs()).isGreaterThanOrEqualTo(0L);
        assertThat(trace.keywordElapsedMs()).isGreaterThanOrEqualTo(0L);
        assertThat(trace.rankElapsedMs()).isGreaterThanOrEqualTo(0L);
        assertThat(trace.totalElapsedMs()).isGreaterThanOrEqualTo(0L);
    }

    @Test
    void recordsRouteSkipWithoutCallingRetrievers() {
        AiIntent intent = new AiIntent();
        intent.setIntent("GENERAL_CHAT");

        var result = searchService.search("你好", intent);

        assertThat(result.strategy()).isEqualTo(ArticleRagSearchStrategy.EMPTY);
        verify(retrieveService, never()).retrieve(anyString());
        verify(keywordRetriever, never()).retrieve(anyString());
        verify(ranker, never()).rankWithEvidence(anyString(), any());

        ArgumentCaptor<ArticleRagRetrievalTrace> captor =
                ArgumentCaptor.forClass(ArticleRagRetrievalTrace.class);
        verify(traceRecorder).record(captor.capture());

        ArticleRagRetrievalTrace trace = captor.getValue();
        assertThat(trace.routed()).isFalse();
        assertThat(trace.skipReason()).isEqualTo("INTENT_NOT_ARTICLE_SEARCH");
        assertThat(trace.strategy()).isEqualTo(ArticleRagSearchStrategy.EMPTY);
        assertThat(trace.vectorCandidates()).isEmpty();
        assertThat(trace.keywordCandidates()).isEmpty();
        assertThat(trace.rankedCandidates()).isEmpty();
    }

    @Test
    void traceRecorderFailureDoesNotBreakArticleSearch() {
        AiIntent intent = new AiIntent();
        intent.setIntent("ARTICLE_SEARCH");
        intent.setKeyWord("Redis");

        ArticleRagContext context = new ArticleRagContext(1L, "Redis 实战", 0, "缓存实践");
        when(retrieveService.retrieve("Redis")).thenReturn(List.of(context));
        when(keywordRetriever.retrieve("Redis")).thenReturn(List.of());
        when(ranker.rankWithEvidence("Redis", List.of(context))).thenReturn(modelResult(List.of(context), 0.90D));
        when(articlesService.listByIds(List.of(1L))).thenReturn(publishedArticles(1L));
        doThrow(new IllegalStateException("trace sink unavailable"))
                .when(traceRecorder)
                .record(any(ArticleRagRetrievalTrace.class));

        var result = searchService.search("Redis", intent);

        assertThat(result.contexts()).containsExactly(context);
    }

    @Test
    void recordsGateDecisionsFromInitialAndFallbackRounds() {
        AiIntent intent = new AiIntent();
        intent.setIntent("ARTICLE_SEARCH");
        intent.setKeyWord("库存超卖");

        ArticleRagContext rejectedVector = new ArticleRagContext(1L, "消息队列", 0, "异步削峰");
        ArticleRagContext acceptedFallback = new ArticleRagContext(2L, "秒杀系统", 1, "库存扣减与防超卖");

        when(retrieveService.retrieve("怎么防止库存超卖")).thenReturn(List.of(rejectedVector));
        when(keywordRetriever.retrieve("库存超卖")).thenReturn(List.of());
        when(keywordRetriever.retrieveFallback("库存超卖")).thenReturn(List.of(acceptedFallback));
        when(ranker.rankWithEvidence("怎么防止库存超卖", List.of(rejectedVector)))
                .thenReturn(modelResult(List.of(rejectedVector), 0.40D));
        when(ranker.rankWithEvidence("怎么防止库存超卖", List.of(rejectedVector, acceptedFallback)))
                .thenReturn(modelResult(List.of(acceptedFallback), 0.90D));
        when(articlesService.listByIds(List.of(2L))).thenReturn(publishedArticles(2L));

        var result = searchService.search("怎么防止库存超卖", intent);

        assertThat(result.contexts()).containsExactly(acceptedFallback);

        ArgumentCaptor<ArticleRagRetrievalTrace> captor =
                ArgumentCaptor.forClass(ArticleRagRetrievalTrace.class);
        verify(traceRecorder).record(captor.capture());

        assertThat(captor.getValue().gateDecisions())
                .extracting(
                        ArticleRagRetrievalTrace.GateDecision::articleId,
                        ArticleRagRetrievalTrace.GateDecision::reason
                )
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(1L, "REJECT_LOW_RELEVANCE"),
                        org.assertj.core.groups.Tuple.tuple(2L, "ACCEPT_RERANK")
                );
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
