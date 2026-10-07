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
    void startsSuspectAndBlocksBothWritesAndDenseSearch() {
        assertThat(healthService.current().state())
                .isEqualTo(ArticleRagIndexHealthService.State.SUSPECT);
        assertThat(healthService.incrementalWritesAllowed()).isFalse();
        // "尚未巡检"不等于"索引健康"：应用停机期间 embedding 可能已经变化，
        // 启动期不能拿未经验证的向量当回答证据。
        assertThat(healthService.denseSearchAllowed()).isFalse();
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
        assertThat(healthService.denseSearchAllowed()).isFalse();
    }

    @Test
    void rebuildStartedFromUnhealthyKeepsDenseSearchBlocked() {
        when(canary.probe(vectorStore))
                .thenReturn(new ArticleRagCanary.ProbeResult(false, 0.31D, "空间漂移"));
        healthService.inspect();
        assertThat(healthService.denseSearchAllowed()).isFalse();

        // 从 UNHEALTHY 发起重建：alias 切换前 active 仍指向那个已确认漂移的索引，
        // 所以 REBUILDING 期间必须继续熔断——否则"开始重建"反而把漂移向量放回检索。
        healthService.beginRebuild();

        assertThat(healthService.current().state())
                .isEqualTo(ArticleRagIndexHealthService.State.REBUILDING);
        assertThat(healthService.denseSearchAllowed()).isFalse();
        assertThat(healthService.incrementalWritesAllowed()).isFalse();
    }

    @Test
    void rebuildStartedFromHealthyKeepsDenseSearchAllowed() {
        when(canary.probe(vectorStore))
                .thenReturn(new ArticleRagCanary.ProbeResult(true, 0.99D, "OK"));
        healthService.inspect();

        healthService.beginRebuild();

        assertThat(healthService.current().state())
                .isEqualTo(ArticleRagIndexHealthService.State.REBUILDING);
        // 重建前健康：旧索引仍在线可用，读取继续放行，避免重建期间检索整体不可用
        assertThat(healthService.denseSearchAllowed()).isTrue();
        assertThat(healthService.incrementalWritesAllowed()).isFalse();
    }

    @Test
    void rebuildStateTemporarilyBlocksIncrementalWrites() {
        ArticleRagIndexHealthService.Snapshot previous = healthService.beginRebuild();

        assertThat(healthService.current().state())
                .isEqualTo(ArticleRagIndexHealthService.State.REBUILDING);
        assertThat(healthService.incrementalWritesAllowed()).isFalse();
        // 初始 SUSPECT 起步 → REBUILDING 继承"不放行"
        assertThat(healthService.denseSearchAllowed()).isFalse();

        healthService.restoreAfterFailedRebuild(previous, "embedding unavailable");
        assertThat(healthService.current().state())
                .isEqualTo(ArticleRagIndexHealthService.State.SUSPECT);
        assertThat(healthService.denseSearchAllowed()).isFalse();
    }
}
