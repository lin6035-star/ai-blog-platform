package com.hailin.blogsystem;

import com.hailin.blogsystem.ai.rag.index.ArticleRagCanary;
import com.hailin.blogsystem.ai.rag.index.ArticleRagIndexHealthService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.VectorStore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ArticleRagIndexHealthServiceTests {

    private final VectorStore vectorStore = mock(VectorStore.class);
    private final ArticleRagCanary canary = mock(ArticleRagCanary.class);
    private final ArticleRagIndexHealthService healthService =
            new ArticleRagIndexHealthService(vectorStore, canary);

    @Test
    void startsSuspectAndBlocksWritesButStillAllowsDenseSearch() {
        assertThat(healthService.current().state())
                .isEqualTo(ArticleRagIndexHealthService.State.SUSPECT);
        assertThat(healthService.incrementalWritesAllowed()).isFalse();
        // SUSPECT 只是"尚未完成首次巡检"，不等于索引坏了；读取必须放行。
        // 若这里也熔断，每次启动都会在 initial-delay 窗口内出现"语义召回全空"。
        assertThat(healthService.denseSearchAllowed()).isTrue();
    }

    @Test
    void scheduledInspectionPublishesHealthyAndUnhealthyStates() {
        when(canary.probe(vectorStore))
                .thenReturn(new ArticleRagCanary.ProbeResult(true, 0.97D, "OK"))
                .thenReturn(new ArticleRagCanary.ProbeResult(false, 0.31D, "canary score too low"));

        healthService.inspect();
        assertThat(healthService.current().state())
                .isEqualTo(ArticleRagIndexHealthService.State.HEALTHY);
        assertThat(healthService.incrementalWritesAllowed()).isTrue();
        assertThat(healthService.denseSearchAllowed()).isTrue();

        healthService.inspect();
        assertThat(healthService.current().state())
                .isEqualTo(ArticleRagIndexHealthService.State.UNHEALTHY);
        assertThat(healthService.incrementalWritesAllowed()).isFalse();
        // 只有 canary 判定"空间漂移"时才熔断读取
        assertThat(healthService.denseSearchAllowed()).isFalse();
    }

    @Test
    void rebuildStateTemporarilyBlocksIncrementalWrites() {
        ArticleRagIndexHealthService.Snapshot previous = healthService.beginRebuild();

        assertThat(healthService.current().state())
                .isEqualTo(ArticleRagIndexHealthService.State.REBUILDING);
        assertThat(healthService.incrementalWritesAllowed()).isFalse();
        // 重建在旁路物理索引上进行，active alias 仍指向可用索引 → 读取放行
        assertThat(healthService.denseSearchAllowed()).isTrue();

        healthService.restoreAfterFailedRebuild(previous, "embedding unavailable");
        assertThat(healthService.current().state())
                .isEqualTo(ArticleRagIndexHealthService.State.SUSPECT);
    }
}
