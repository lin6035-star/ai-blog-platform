package com.hailin.blogsystem;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.json.JsonData;
import co.elastic.clients.transport.ElasticsearchTransport;
import com.hailin.blogsystem.ai.memory.AiEpisodicMemoryIndexService;
import com.hailin.blogsystem.ai.memory.AiMemoryIndexService;
import com.hailin.blogsystem.ai.memory.MemoryRagReindexService;
import com.hailin.blogsystem.config.BlogAiProperties;
import com.hailin.blogsystem.entity.AiEpisodicMemories;
import com.hailin.blogsystem.entity.AiUserMemories;
import com.hailin.blogsystem.mapper.AiEpisodicMemoryMapper;
import com.hailin.blogsystem.mapper.AiUserMemoryMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.LongPredicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MemoryRagReindexServiceTests {

    private final AiUserMemoryMapper userMemoryMapper = mock(AiUserMemoryMapper.class);
    private final AiEpisodicMemoryMapper episodicMemoryMapper = mock(AiEpisodicMemoryMapper.class);
    private final AiMemoryIndexService memoryIndexService = mock(AiMemoryIndexService.class);
    private final AiEpisodicMemoryIndexService episodicMemoryIndexService =
            mock(AiEpisodicMemoryIndexService.class);
    private final ElasticsearchClient elasticsearchClient = mock(ElasticsearchClient.class);
    private final BlogAiProperties properties = new BlogAiProperties();

    private final MemoryRagReindexService service = new MemoryRagReindexService(
            userMemoryMapper,
            episodicMemoryMapper,
            memoryIndexService,
            episodicMemoryIndexService,
            elasticsearchClient,
            properties
    );

    @Test
    void reindexesUserAndEpisodicMemories() {
        when(userMemoryMapper.selectList(any())).thenReturn(List.of(userMemory(1L), userMemory(2L)));
        when(episodicMemoryMapper.selectList(any())).thenReturn(List.of(episodicMemory(3L)));

        MemoryRagReindexService.ReindexOutcome outcome = service.reindex();

        assertThat(outcome.userMemories()).isEqualTo(2);
        assertThat(outcome.episodicMemories()).isEqualTo(1);
        assertThat(outcome.purgedOrphans()).isZero();
        verify(memoryIndexService, times(2)).indexMemory(any(AiUserMemories.class));
        verify(episodicMemoryIndexService).indexMemory(any(AiEpisodicMemories.class));
    }

    @Test
    void skipsOrphanPurgeWhenIndexCannotBeRead() {
        // ES 侧读不到（mock 未 stub / 查询失败）时，必须整体跳过清理：
        // 否则会把 MySQL 里仍在的记忆全部误判成孤儿删掉。
        when(userMemoryMapper.selectList(any())).thenReturn(List.of(userMemory(1L)));
        when(episodicMemoryMapper.selectList(any())).thenReturn(List.of(episodicMemory(2L)));

        MemoryRagReindexService.ReindexOutcome outcome = service.reindex();

        assertThat(outcome.purgedOrphans()).isZero();
        verify(memoryIndexService, never()).deleteMemoryIndex(any());
        verify(episodicMemoryIndexService, never()).deleteMemoryIndex(any());
    }

    @Test
    void purgeCandidatesRequireDatabaseReconfirmationBeforeDeletion() {
        // ES 中存在只代表“待确认”：重刷期间可能发生新增或删除，
        // 真正删除前必须以 MySQL 当前状态为准。
        List<Long> candidates = List.of(1L, 2L, 3L);
        LongPredicate stillPresent = id -> id == 2L;

        assertThat(MemoryRagReindexService.confirmPurgeCandidates(candidates, stillPresent))
                .containsExactly(1L, 3L);
        assertThat(MemoryRagReindexService.confirmPurgeCandidates(List.of(), stillPresent)).isEmpty();
        assertThat(MemoryRagReindexService.confirmPurgeCandidates(null, stillPresent)).isEmpty();
    }

    @Test
    void purgesMemoryDeletedAfterSnapshotEvenWhenStaleSnapshotWasReindexed() throws Exception {
        AiUserMemories staleMemory = userMemory(1L);
        when(userMemoryMapper.selectList(any())).thenReturn(List.of(staleMemory));
        when(episodicMemoryMapper.selectList(any())).thenReturn(List.of());
        when(userMemoryMapper.selectById(1L)).thenReturn(null);
        ElasticsearchTransport transport = mock(ElasticsearchTransport.class);
        doAnswer(invocation -> searchResponse(1L))
                .doAnswer(invocation -> searchResponse())
                .when(transport).performRequest(any(), any(), any());
        MemoryRagReindexService reindexService = new MemoryRagReindexService(
                userMemoryMapper,
                episodicMemoryMapper,
                memoryIndexService,
                episodicMemoryIndexService,
                new ElasticsearchClient(transport),
                properties
        );

        MemoryRagReindexService.ReindexOutcome outcome = reindexService.reindex();

        verify(memoryIndexService).indexMemory(staleMemory);
        verify(memoryIndexService).deleteMemoryIndex(1L);
        assertThat(outcome.purgedOrphans()).isEqualTo(1);
    }

    @Test
    void doesNothingWhenThereAreNoMemories() {
        when(userMemoryMapper.selectList(any())).thenReturn(List.of());
        when(episodicMemoryMapper.selectList(any())).thenReturn(List.of());

        MemoryRagReindexService.ReindexOutcome outcome = service.reindex();

        assertThat(outcome.userMemories()).isZero();
        assertThat(outcome.episodicMemories()).isZero();
        assertThat(outcome.purgedOrphans()).isZero();
        verifyNoInteractions(memoryIndexService, episodicMemoryIndexService);
    }

    private AiUserMemories userMemory(Long id) {
        AiUserMemories memory = new AiUserMemories();
        memory.setId(id);
        memory.setUserId(100L);
        return memory;
    }

    private AiEpisodicMemories episodicMemory(Long id) {
        AiEpisodicMemories memory = new AiEpisodicMemories();
        memory.setId(id);
        memory.setUserId(100L);
        return memory;
    }

    private SearchResponse<JsonData> searchResponse(Long... memoryIds) {
        List<Hit<JsonData>> hits = java.util.Arrays.stream(memoryIds)
                .map(id -> Hit.<JsonData>of(hit -> hit
                        .index("memory-test")
                        .id("memory:" + id)
                        .source(JsonData.fromJson("{\"metadata\":{\"memoryId\":" + id + "}}"))))
                .toList();
        return SearchResponse.of(response -> response
                .took(0)
                .timedOut(false)
                .shards(shards -> shards.total(1).successful(1).failed(0))
                .hits(searchHits -> searchHits.hits(hits)));
    }
}
