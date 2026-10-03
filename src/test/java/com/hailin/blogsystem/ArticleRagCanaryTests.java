package com.hailin.blogsystem;

import com.hailin.blogsystem.ai.rag.index.ArticleRagCanary;
import com.hailin.blogsystem.config.BlogAiProperties;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ArticleRagCanaryTests {

    private final BlogAiProperties properties = new BlogAiProperties();
    private final VectorStore vectorStore = mock(VectorStore.class);
    private final ArticleRagCanary canary = new ArticleRagCanary(properties);

    @Test
    void writesDedicatedCanaryDocumentIntoRebuildTarget() {
        canary.writeTo(vectorStore);

        ArgumentCaptor<List<Document>> captor = ArgumentCaptor.forClass(List.class);
        verify(vectorStore).add(captor.capture());
        assertThat(captor.getValue()).singleElement().satisfies(document -> {
            assertThat(document.getId()).isEqualTo(ArticleRagCanary.DOCUMENT_ID);
            assertThat(document.getText()).isEqualTo(ArticleRagCanary.TEXT);
            assertThat(document.getMetadata()).containsEntry("source", ArticleRagCanary.SOURCE);
        });
    }

    @Test
    void probeAcceptsCanaryOnlyWhenRawCosinePassesThreshold() {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(
                Document.builder()
                        .id(ArticleRagCanary.DOCUMENT_ID)
                        .text(ArticleRagCanary.TEXT)
                        .metadata("source", ArticleRagCanary.SOURCE)
                        .score(0.96D)
                        .build()
        ));

        ArticleRagCanary.ProbeResult result = canary.probe(vectorStore);

        assertThat(result.healthy()).isTrue();
        assertThat(result.score()).isEqualTo(0.96D);

        ArgumentCaptor<SearchRequest> requestCaptor = ArgumentCaptor.forClass(SearchRequest.class);
        verify(vectorStore).similaritySearch(requestCaptor.capture());
        assertThat(requestCaptor.getValue().getSimilarityThreshold()).isZero();
        assertThat(requestCaptor.getValue().hasFilterExpression()).isTrue();
    }

    @Test
    void probeRejectsLowScoreCanary() {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(
                Document.builder()
                        .id(ArticleRagCanary.DOCUMENT_ID)
                        .text(ArticleRagCanary.TEXT)
                        .metadata("source", ArticleRagCanary.SOURCE)
                        .score(0.42D)
                        .build()
        ));

        ArticleRagCanary.ProbeResult result = canary.probe(vectorStore);

        assertThat(result.healthy()).isFalse();
        assertThat(result.reason()).contains("0.42").contains("0.9");
    }
}
