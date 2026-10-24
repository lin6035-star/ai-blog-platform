package com.hailin.blogsystem.ai.rag;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class LoggingArticleRagTraceRecorder implements ArticleRagTraceRecorder {

    @Override
    public void record(ArticleRagRetrievalTrace trace) {
        long accepted = trace.gateDecisions().stream()
                .filter(decision -> decision.reason() != null && decision.reason().startsWith("ACCEPT"))
                .count();
        long rejected = trace.gateDecisions().size() - accepted;

        log.info(
                "[RAG-TRACE] intent={}, routed={}, skipReason={}, lexicalQuery={}, "
                        + "vector={}, keyword={}, merged={}, ranked={}, final={}, strategy={}, gateApplied={}, "
                        + "keywordFallback={}, gateAccepted={}, gateRejected={}, "
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
                trace.keywordFallbackTriggered(),
                accepted,
                rejected,
                trace.vectorElapsedMs(),
                trace.keywordElapsedMs(),
                trace.rankElapsedMs(),
                trace.totalElapsedMs(),
                trace.finalCandidates()
        );
    }
}
