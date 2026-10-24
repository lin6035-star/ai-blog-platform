package com.hailin.blogsystem.entity.vo;

import com.hailin.blogsystem.ai.rag.index.ArticleRagIndexHealthService;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.time.ZoneId;

/** 文章 RAG 索引健康快照（只读），用于线上定位“RAG 为什么不返回”。 */
@Data
@Builder
public class ArticleRagIndexHealthVO {

    /** HEALTHY / SUSPECT / UNHEALTHY / REBUILDING */
    private String state;
    private String reason;
    /** canary 探针返回的原始 cosine；尚未巡检时为 null */
    private Double canaryScore;
    private LocalDateTime checkedAt;
    /** 当前 alias 指向的物理索引；REBUILDING / 未记录时为 null */
    private String physicalIndexName;
    /** dense 检索是否放行（仅 UNHEALTHY 熔断） */
    private boolean denseSearchAllowed;
    /** 是否允许局部 Embedding 写入（仅 HEALTHY 放行） */
    private boolean incrementalWritesAllowed;

    public static ArticleRagIndexHealthVO from(ArticleRagIndexHealthService.Snapshot snapshot,
                                               boolean denseSearchAllowed,
                                               boolean incrementalWritesAllowed) {
        return ArticleRagIndexHealthVO.builder()
                .state(snapshot == null || snapshot.state() == null ? null : snapshot.state().name())
                .reason(snapshot == null ? null : snapshot.reason())
                .canaryScore(snapshot == null ? null : snapshot.canaryScore())
                .checkedAt(snapshot == null || snapshot.checkedAt() == null
                        ? null
                        : LocalDateTime.ofInstant(snapshot.checkedAt(), ZoneId.systemDefault()))
                .physicalIndexName(snapshot == null ? null : snapshot.physicalIndexName())
                .denseSearchAllowed(denseSearchAllowed)
                .incrementalWritesAllowed(incrementalWritesAllowed)
                .build();
    }
}
