package com.hailin.blogsystem.ai.rag;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class LoggingArticleRagTraceRecorder implements ArticleRagTraceRecorder {

    @Override
    public void record(ArticleRagRetrievalTrace trace) {
        log.info(
                "[RAG-TRACE] intent={}, routed={}, skipReason={}, lexicalQuery={}, "
                        + "vector={}, keyword={}, merged={}, ranked={}, final={}, strategy={}, gateApplied={}, "
                        + "elapsedMs={{vector:{}, keyword:{}, rank:{}, total:{}}}, finalCandidates={}",
                trace.intent(),
                trace.routed(),
                trace.skipReason(),
                trace.lexicalQuery(),
                trace.vectorCandidates().size(),
                trace.keywordCandidates().size(),
                trace.mergedCandidates().size(),
                trace.rankedCandidates().size(),
                trace.finalCandidates().size(),
                trace.strategy(),
                trace.gateApplied(),
                trace.vectorElapsedMs(),
                trace.keywordElapsedMs(),
                trace.rankElapsedMs(),
                trace.totalElapsedMs(),
                trace.finalCandidates()
        );
    }
}
