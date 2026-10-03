package com.hailin.blogsystem.ai.rag.index;

import java.time.Instant;

public record ArticleRagIndexManifest(
        String physicalIndexName,
        String embeddingModel,
        int dimensions,
        String chunkingVersion,
        Instant createdAt,
        long articleCount,
        long chunkCount,
        String contentHash
) {
}
