package com.hailin.blogsystem;


import com.hailin.blogsystem.ai.index.ArticleIndexOutbox;
import com.hailin.blogsystem.ai.index.ArticleIndexOutboxService;
import com.hailin.blogsystem.ai.rag.ArticleRagConsistencyService;
import com.hailin.blogsystem.ai.rag.ArticleRagController;
import com.hailin.blogsystem.ai.rag.ArticleRagRetrieveService;
import com.hailin.blogsystem.ai.rag.index.ArticleRagIndexHealthService;
import com.hailin.blogsystem.ai.rag.index.ArticleRagRebuildService;
import com.hailin.blogsystem.ai.rag.index.IndexManagementAccessGuard;
import com.hailin.blogsystem.config.BlogAiProperties;
import com.hailin.blogsystem.utils.UserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ArticleRagControllerTests {

    private final ArticleRagRetrieveService articleRagRetrieveService = mock(ArticleRagRetrieveService.class);
    private final ArticleRagConsistencyService articleRagConsistencyService = mock(ArticleRagConsistencyService.class);
    private final ArticleRagRebuildService articleRagRebuildService = mock(ArticleRagRebuildService.class);
    private final ArticleRagIndexHealthService articleRagIndexHealthService = mock(ArticleRagIndexHealthService.class);
    private final ArticleIndexOutboxService articleIndexOutboxService = mock(ArticleIndexOutboxService.class);
    private final BlogAiProperties blogAiProperties = mock(BlogAiProperties.class);
    // 用真实 guard + mock 配置：既测接口行为，也顺带验证 allowlist 边界本身生效
    private final ArticleRagController controller = new ArticleRagController(
            articleRagRetrieveService,
            articleRagConsistencyService,
            articleRagRebuildService,
            articleRagIndexHealthService,
            new IndexManagementAccessGuard(blogAiProperties),
            articleIndexOutboxService
    );
    private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

    @BeforeEach
    void setUp() {
        UserContext.set(1L);

        var rag = mock(BlogAiProperties.Rag.class);
        var rebuild = new BlogAiProperties.Rebuild();
        rebuild.setEnabled(true);
        rebuild.setAllowedUserIds(List.of(1L));
        when(blogAiProperties.getRag()).thenReturn(rag);
        when(rag.getRebuild()).thenReturn(rebuild);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void rebuildsPublishedArticleRagIndexInIsolation() throws Exception {
        when(articleRagRebuildService.rebuild()).thenReturn(
                new ArticleRagRebuildService.RebuildOutcome("article-rag-new", 12, List.of("article-rag-old"))
        );

        mockMvc.perform(post("/api/ai/rag/articles/rebuild"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.message").value("success"))
                .andExpect(jsonPath("$.data.indexedChunkCount").value(12));

        verify(articleRagRebuildService).rebuild();
    }

    @Test
    void consistencyEndpointsReuseRebuildAllowlist() throws Exception {
        UserContext.set(99L);

        mockMvc.perform(get("/api/ai/rag/articles/consistency"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(403));
        mockMvc.perform(post("/api/ai/rag/articles/consistency/repair"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(403));

        verify(articleRagConsistencyService, never()).check();
        verify(articleRagConsistencyService, never()).repair();
    }

    @Test
    void exposesIndexHealthAndReusesRebuildAllowlist() throws Exception {
        when(articleRagIndexHealthService.current()).thenReturn(new ArticleRagIndexHealthService.Snapshot(
                ArticleRagIndexHealthService.State.SUSPECT,
                "尚未完成首次 canary 巡检",
                null,
                Instant.now(),
                null
        ));
        when(articleRagIndexHealthService.denseSearchAllowed()).thenReturn(true);
        when(articleRagIndexHealthService.incrementalWritesAllowed()).thenReturn(false);

        mockMvc.perform(get("/api/ai/rag/articles/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.state").value("SUSPECT"))
                .andExpect(jsonPath("$.data.denseSearchAllowed").value(true))
                .andExpect(jsonPath("$.data.incrementalWritesAllowed").value(false));

        UserContext.set(99L);
        mockMvc.perform(get("/api/ai/rag/articles/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    void exposesOutboxStatsAndReusesRebuildAllowlist() throws Exception {
        when(articleIndexOutboxService.countByStatus(ArticleIndexOutbox.STATUS_PENDING)).thenReturn(2L);

        mockMvc.perform(get("/api/ai/rag/articles/index-outbox/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.pending").value(2));

        UserContext.set(99L);
        mockMvc.perform(get("/api/ai/rag/articles/index-outbox/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(403));
    }
}
