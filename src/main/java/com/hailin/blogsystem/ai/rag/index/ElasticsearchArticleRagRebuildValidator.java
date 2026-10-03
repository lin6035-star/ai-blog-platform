package com.hailin.blogsystem.ai.rag.index;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldValue;
import com.hailin.blogsystem.ai.rag.index.ArticleRagRebuildService.Validator;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
public class ElasticsearchArticleRagRebuildValidator implements Validator {

    private final ElasticsearchClient elasticsearchClient;

    public ElasticsearchArticleRagRebuildValidator(ElasticsearchClient elasticsearchClient) {
        this.elasticsearchClient = elasticsearchClient;
    }

    @Override
    public void validate(String physicalIndexName, int expectedChunkCount) throws IOException {
        // VectorStore bulk 写入默认不强制 refresh；先刷新再做数量校验，避免把可见性延迟误判成丢数据。
        elasticsearchClient.indices().refresh(r -> r.index(physicalIndexName));
        long actual = elasticsearchClient.count(c -> c
                .index(physicalIndexName)
                .query(q -> q.bool(b -> b.filter(f -> f.term(t -> t
                        .field("metadata.source.keyword")
                        .value(FieldValue.of("article"))
                ))))
        ).count();
        if (actual != expectedChunkCount) {
            throw new IllegalStateException(
                    "文章 RAG 新索引 chunk 数不一致: expected=" + expectedChunkCount + ", actual=" + actual
            );
        }
    }
}
