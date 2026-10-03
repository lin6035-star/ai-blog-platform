package com.hailin.blogsystem;

import com.hailin.blogsystem.ai.rag.ArticleRagRetrieveService;
import com.hailin.blogsystem.ai.rag.index.ArticleRagIndexHealthService;
import com.hailin.blogsystem.config.BlogAiProperties;
import com.hailin.blogsystem.entity.dto.ArticleRagContext;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;

import java.util.List;
import java.util.Map;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ArticleRagRetrieveServiceTests {

    private final BlogAiProperties blogAiProperties = new BlogAiProperties();
    private final VectorStore vectorStore = mock(VectorStore.class);
    private final ArticleRagIndexHealthService healthService = mock(ArticleRagIndexHealthService.class);
    private final ArticleRagRetrieveService retrieveService =
            new ArticleRagRetrieveService(blogAiProperties, vectorStore, healthService);

    ArticleRagRetrieveServiceTests() {
        when(healthService.denseSearchAllowed()).thenReturn(true);
    }

    @Test
    void returnsEmptyListWhenQuestionIsBlank() {
        List<ArticleRagContext> contexts = retrieveService.retrieve(" ");

        assertThat(contexts).isEmpty();
        verify(vectorStore, never()).similaritySearch(any(SearchRequest.class));
    }

    @Test
    void convertsArticleDocumentsToRagContexts() {
        Document document = new Document(
                "Redis 可以用来做缓存、排行榜、点赞状态和浏览量统计。",
                Map.of(
                        "source", "article",
                        "articleId", 1L,
                        "title", "Redis 实战",
                        "chunkIndex", 0
                )
        );

        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(document));

        List<ArticleRagContext> contexts = retrieveService.retrieve("Redis 能做什么");

        assertThat(contexts).hasSize(1);

        ArticleRagContext context = contexts.get(0);
        assertThat(context.articleId()).isEqualTo(1L);
        assertThat(context.title()).isEqualTo("Redis 实战");
        assertThat(context.chunkIndex()).isZero();
        assertThat(context.content()).contains("Redis 可以用来做缓存");
    }

    @Test
    void skipsDenseRetrievalWhenIndexIsUnhealthy() {
        when(healthService.denseSearchAllowed()).thenReturn(false);
        when(healthService.current()).thenReturn(new ArticleRagIndexHealthService.Snapshot(
                ArticleRagIndexHealthService.State.UNHEALTHY,
                "canary mismatch",
                0.2D,
                Instant.now(),
                "article-rag-old"
        ));

        List<ArticleRagContext> contexts = retrieveService.retrieve("Redis 能做什么");

        assertThat(contexts).isEmpty();
        verify(vectorStore, never()).similaritySearch(any(SearchRequest.class));
    }

    @Test
    void stillSearchesWhileFirstCanaryInspectionHasNotRun() {
        // SUSPECT（首次巡检未完成）必须照常检索：否则每次应用启动后的
        // initial-delay 窗口内，站内语义召回会静默变空。
        when(healthService.denseSearchAllowed()).thenReturn(true);
        when(healthService.current()).thenReturn(new ArticleRagIndexHealthService.Snapshot(
                ArticleRagIndexHealthService.State.SUSPECT,
                "尚未完成首次 canary 巡检",
                null,
                Instant.now(),
                null
        ));
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

        retrieveService.retrieve("Redis 能做什么");

        verify(vectorStore).similaritySearch(any(SearchRequest.class));
    }

    @Test
    void usesRawContentInsteadOfEmbeddingContextForPromptAndReferences() {
        Document document = new Document(
                "文章标题：Redis 实战\n章节：缓存一致性\n原始正文",
                Map.of(
                        "source", "article",
                        "articleId", 1L,
                        "title", "Redis 实战",
                        "chunkIndex", 0,
                        "rawContent", "原始正文"
                )
        );
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(document));

        List<ArticleRagContext> contexts = retrieveService.retrieve("Redis");

        assertThat(contexts).singleElement()
                .extracting(ArticleRagContext::content)
                .isEqualTo("原始正文");
    }

    @Test
    void ignoresNonArticleDocuments() {
        Document document = new Document(
                "smoke test document",
                Map.of("source", "smoke-test")
        );

        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(document));

        List<ArticleRagContext> contexts = retrieveService.retrieve("Redis");

        assertThat(contexts).isEmpty();
    }

    @Test
    void restrictsVectorSearchToArticleDocuments() {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

        retrieveService.retrieve("Redis");

        var captor = forClass(SearchRequest.class);
        verify(vectorStore).similaritySearch(captor.capture());

        SearchRequest searchRequest = captor.getValue();
        assertThat(searchRequest.getTopK()).isEqualTo(10);
        assertThat(searchRequest.getSimilarityThreshold()).isEqualTo(0.55D);
        assertThat(searchRequest.hasFilterExpression()).isTrue();

        Filter.Expression expression = searchRequest.getFilterExpression();
        assertThat(expression.type()).isEqualTo(Filter.ExpressionType.AND);
        assertThat(expression.left()).isEqualTo(new Filter.Expression(
                Filter.ExpressionType.EQ,
                new Filter.Key("source"),
                new Filter.Value("article")
        ));
        assertThat(expression.right()).isEqualTo(new Filter.Expression(
                Filter.ExpressionType.EQ,
                new Filter.Key("articleStatus"),
                new Filter.Value(1)
        ));
    }
}
