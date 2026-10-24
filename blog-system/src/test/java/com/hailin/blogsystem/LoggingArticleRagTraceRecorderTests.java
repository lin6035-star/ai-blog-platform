package com.hailin.blogsystem;

import com.hailin.blogsystem.ai.rag.ArticleRagRetrievalTrace;
import com.hailin.blogsystem.ai.rag.LoggingArticleRagTraceRecorder;
import com.hailin.blogsystem.entity.dto.ArticleRagSearchStrategy;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * {@code LoggingArticleRagTraceRecorder} 此前没有任何测试覆盖，而它刚被改过
 * （新增 keywordFallback 与 Gate accept/reject 计数）。这里锁住两个边界：
 * 正常 trace，以及 {@code gateDecisions} 为空 / {@code reason} 为 null 的情况
 * ——后者曾是最容易 NPE 的地方。
 */
class LoggingArticleRagTraceRecorderTests {

    private final LoggingArticleRagTraceRecorder recorder = new LoggingArticleRagTraceRecorder();

    @Test
    void recordsTraceWithGateDecisions() {
        ArticleRagRetrievalTrace trace = new ArticleRagRetrievalTrace(
                "Redis 缓存怎么设计", "ARTICLE_SEARCH", "Redis 缓存", true, null,
                List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(
                        new ArticleRagRetrievalTrace.GateDecision(1L, 0, 0.91D, "ACCEPT_RERANK"),
                        new ArticleRagRetrievalTrace.GateDecision(2L, 3, 0.42D, "REJECT_LOW_RELEVANCE")
                ),
                ArticleRagSearchStrategy.HYBRID, true, true,
                12L, 5L, 300L, 320L
        );

        assertThatCode(() -> recorder.record(trace)).doesNotThrowAnyException();
    }

    @Test
    void handlesSkippedTraceAndNullGateReason() {
        // skip 分支：未进入检索，没有候选也没有 Gate 裁决
        ArticleRagRetrievalTrace skipped = new ArticleRagRetrievalTrace(
                "你好", "GENERAL_CHAT", null, false, "INTENT_NOT_ARTICLE_SEARCH",
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                ArticleRagSearchStrategy.EMPTY, false, false,
                0L, 0L, 0L, 1L
        );
        assertThatCode(() -> recorder.record(skipped)).doesNotThrowAnyException();

        // reason 为 null：accepted/rejected 统计不能因此 NPE
        ArticleRagRetrievalTrace nullReason = new ArticleRagRetrievalTrace(
                "边界样例", "ARTICLE_SEARCH", null, true, null,
                List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(new ArticleRagRetrievalTrace.GateDecision(1L, 0, null, null)),
                ArticleRagSearchStrategy.EMPTY, true, false,
                0L, 0L, 0L, 1L
        );
        assertThatCode(() -> recorder.record(nullReason)).doesNotThrowAnyException();
    }
}
