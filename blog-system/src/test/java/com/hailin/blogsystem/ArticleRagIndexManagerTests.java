package com.hailin.blogsystem;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hailin.blogsystem.ai.rag.index.ArticleRagIndexManager;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ArticleRagIndexManagerTests {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ArticleRagIndexManager manager = new ArticleRagIndexManager(
            null,
            objectMapper,
            Clock.fixed(Instant.parse("2026-10-03T12:34:56Z"), ZoneOffset.UTC)
    );

    @Test
    void mappingDeclaresArticleSearchAndVectorFields() throws Exception {
        JsonNode mapping = objectMapper.readTree(manager.mappingJson());
        JsonNode properties = mapping.path("mappings").path("properties");

        assertThat(properties.has("content")).isTrue();
        assertThat(properties.has("metadata")).isTrue();
        assertThat(properties.has("embedding")).isTrue();
        JsonNode metadataProperties = properties.path("metadata").path("properties");
        assertThat(metadataProperties.has("title")).isTrue();
        assertThat(metadataProperties.has("headingPath")).isTrue();
        assertThat(metadataProperties.has("rawContent")).isTrue();
        assertThat(metadataProperties.has("source")).isTrue();
        assertThat(metadataProperties.path("source").path("fields").has("keyword")).isTrue();
        assertThat(metadataProperties.has("articleId")).isTrue();
        assertThat(metadataProperties.has("articleStatus")).isTrue();
        assertThat(metadataProperties.has("chunkIndex")).isTrue();
        assertThat(properties.path("embedding").path("type").textValue()).isEqualTo("dense_vector");
    }

    @Test
    void physicalIndexNameContainsModelDimensionsChunkVersionAndTimestamp() {
        String name = manager.physicalIndexName("text-embedding-v3", 1024, "chunk-v2");

        assertThat(name).isEqualTo("article-rag-text-embedding-v3-1024-chunk-v2-20261003-123456");
    }

    @Test
    void aliasSwitchRequestRemovesKnownTargetsAndAddsExactlyOneActiveTarget() throws Exception {
        String body = manager.aliasSwitchBody("article-rag-text-embedding-v3-1024-chunk-v2-20261003-123456", List.of("article-rag-old"));
        JsonNode actions = objectMapper.readTree(body).path("actions");

        assertThat(actions).hasSize(2);
        assertThat(actions.get(0).path("remove").path("alias").textValue()).isEqualTo("article_rag_active");
        assertThat(actions.get(0).path("remove").path("index").textValue()).isEqualTo("article-rag-old");
        assertThat(actions.get(1).path("add").path("alias").textValue()).isEqualTo("article_rag_active");
        assertThat(actions.get(1).path("add").path("index").textValue())
                .isEqualTo("article-rag-text-embedding-v3-1024-chunk-v2-20261003-123456");
        assertThat(actions.get(1).path("add").path("is_write_index").isBoolean()).isTrue();
        assertThat(actions.get(1).path("add").path("is_write_index").booleanValue()).isTrue();
    }
}
