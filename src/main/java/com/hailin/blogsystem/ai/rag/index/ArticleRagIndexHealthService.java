package com.hailin.blogsystem.ai.rag.index;

import com.hailin.blogsystem.config.AiVectorStoreConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

/** 文章向量索引的运行期健康状态；局部写入只在 HEALTHY 时放行。 */
@Slf4j
@Service
public class ArticleRagIndexHealthService {

    private final VectorStore vectorStore;
    private final ArticleRagCanary canary;
    private final AtomicReference<Snapshot> current = new AtomicReference<>(
            new Snapshot(State.SUSPECT, "尚未完成首次 canary 巡检", null, Instant.now(), null)
    );

    public ArticleRagIndexHealthService(
            @Qualifier(AiVectorStoreConfig.ARTICLE_RAG_VECTOR_STORE) VectorStore vectorStore,
            ArticleRagCanary canary
    ) {
        this.vectorStore = vectorStore;
        this.canary = canary;
    }

    @Scheduled(
            initialDelayString = "${blog.ai.rag.health.initial-delay-ms:10000}",
            fixedDelayString = "${blog.ai.rag.health.interval-ms:300000}"
    )
    public void inspect() {
        if (current.get().state() == State.REBUILDING) {
            return;
        }
        try {
            ArticleRagCanary.ProbeResult result = canary.probe(vectorStore);
            State state = result.healthy() ? State.HEALTHY : State.UNHEALTHY;
            current.set(new Snapshot(state, result.reason(), result.score(), Instant.now(), null));
            if (result.healthy()) {
                log.info("文章 RAG 索引 canary 健康，cosine={}", result.score());
            } else {
                log.error("文章 RAG 索引 canary 异常，cosine={}，reason={}", result.score(), result.reason());
            }
        } catch (Exception e) {
            current.set(new Snapshot(State.UNHEALTHY,
                    "canary 巡检失败: " + e.getClass().getSimpleName() + ": " + safeMessage(e),
                    null, Instant.now(), null));
            log.error("文章 RAG 索引 canary 巡检失败", e);
        }
    }

    public boolean incrementalWritesAllowed() {
        return current.get().state() == State.HEALTHY;
    }

    /**
     * 检索放行判定。**与写入判定不是同一个问题**，因此分开：
     *
     * <ul>
     *   <li>{@code UNHEALTHY}：canary 已判定 embedding 空间漂移 → dense 结果不可作为证据，熔断；</li>
     *   <li>{@code SUSPECT}：只是"尚未完成首次巡检"，不等于索引坏了 → **放行**。
     *       若这里也熔断，每次启动都会在 {@code blog.ai.rag.health.initial-delay-ms}
     *       窗口内出现"站内语义召回全空"，表现为 RAG 突然失效；</li>
     *   <li>{@code REBUILDING}：新索引在旁路构建，active alias 仍指向可用索引 → **放行**；</li>
     *   <li>{@code HEALTHY}：放行。</li>
     * </ul>
     */
    public boolean denseSearchAllowed() {
        return current.get().state() != State.UNHEALTHY;
    }

    public Snapshot beginRebuild() {
        Snapshot previous = current.get();
        current.set(new Snapshot(State.REBUILDING, "隔离索引正在构建", null, Instant.now(), null));
        return previous;
    }

    public void completeRebuild(String physicalIndexName, ArticleRagCanary.ProbeResult result) {
        if (!result.healthy()) {
            throw new IllegalArgumentException("不能用未通过 canary 的索引完成重建");
        }
        current.set(new Snapshot(State.HEALTHY, result.reason(), result.score(), Instant.now(), physicalIndexName));
    }

    public void restoreAfterFailedRebuild(Snapshot previous, String failureReason) {
        Snapshot restored = previous == null
                ? new Snapshot(State.SUSPECT, failureReason, null, Instant.now(), null)
                : new Snapshot(previous.state(), failureReason, previous.canaryScore(), Instant.now(),
                        previous.physicalIndexName());
        current.set(restored);
    }

    public Snapshot current() {
        return current.get();
    }

    private String safeMessage(Exception e) {
        return e.getMessage() == null ? "" : e.getMessage();
    }

    public enum State {
        HEALTHY,
        SUSPECT,
        UNHEALTHY,
        REBUILDING
    }

    public record Snapshot(
            State state,
            String reason,
            Double canaryScore,
            Instant checkedAt,
            String physicalIndexName
    ) {
    }
}
