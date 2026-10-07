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

    /**
     * 重建开始前的读取许可。
     *
     * <p>{@code REBUILDING} 必须**继承**它，不能无条件放行：从 {@code UNHEALTHY} 发起重建时，
     * active alias 在切换前仍指向那个已确认漂移的索引，此时放开读取等于把漂移向量重新当作证据。
     */
    private final AtomicReference<Boolean> readAllowedBeforeRebuild = new AtomicReference<>(false);

    public ArticleRagIndexHealthService(
            @Qualifier(AiVectorStoreConfig.ARTICLE_RAG_VECTOR_STORE) VectorStore vectorStore,
            ArticleRagCanary canary
    ) {
        this.vectorStore = vectorStore;
        this.canary = canary;
    }

    @Scheduled(
            // 首次巡检尽量早：SUSPECT 期间 dense 不放行，延迟直接等于"启动后语义召回不可用"的窗口。
            // 取 1s 而不是 0：给 ES / embedding 客户端一点预热余量——首次探测若因瞬时未就绪而失败，
            // 会被判成 UNHEALTHY 并熔断整整一个 interval（5 分钟），代价远大于多等 1 秒。
            initialDelayString = "${blog.ai.rag.health.initial-delay-ms:1000}",
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
     * 检索放行判定。**与写入判定不是同一个问题**，因此分开；策略是「只有已知健康才放行」：
     *
     * <ul>
     *   <li>{@code HEALTHY}：放行；</li>
     *   <li>{@code SUSPECT}（尚未完成首次巡检）：**不放行**。停机期间 embedding 服务可能已变化，
     *       "还没巡检"不等于"索引健康"，不能拿未经验证的向量当回答证据；</li>
     *   <li>{@code UNHEALTHY}（canary 已判定空间漂移）：不放行；</li>
     *   <li>{@code REBUILDING}：**继承重建前的许可**——重建前健康才放行，
     *       从 {@code UNHEALTHY} 起步的重建全程保持熔断，直到新索引通过 canary 切换完成。</li>
     * </ul>
     *
     * <p>为把 {@code SUSPECT} 的代价压到最小，首次巡检延迟已从 10s 降到 1s
     * （{@code blog.ai.rag.health.initial-delay-ms}），启动窗口只剩一次 canary 探测的时间。
     */
    public boolean denseSearchAllowed() {
        State state = current.get().state();
        if (state == State.REBUILDING) {
            return Boolean.TRUE.equals(readAllowedBeforeRebuild.get());
        }
        return state == State.HEALTHY;
    }

    public Snapshot beginRebuild() {
        Snapshot previous = current.get();
        readAllowedBeforeRebuild.set(previous.state() == State.HEALTHY);
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
