package com.hailin.blogsystem.ai.memory;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.json.JsonData;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hailin.blogsystem.config.BlogAiProperties;
import com.hailin.blogsystem.entity.AiEpisodicMemories;
import com.hailin.blogsystem.entity.AiUserMemories;
import com.hailin.blogsystem.mapper.AiEpisodicMemoryMapper;
import com.hailin.blogsystem.mapper.AiUserMemoryMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.json.JsonObject;
import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.LongPredicate;

/**
 * 按 MySQL 权威数据重刷 Memory 的存量向量，并对齐两侧的文档集合。
 *
 * <p><b>为什么需要它</b>：正式记忆与情景记忆仍写在早期共享索引
 * （{@code blog.ai.rag.corpus.memory-index-name} 指回 {@code spring-ai-document-index}），
 * 其**存量向量与当前 embedding 空间不同源**（2026-10-03 实测 cosine ≈ 0），
 * 在返回阈值下恒空召回；而新产生的记忆写的是当前模型向量 ——
 * 同一个索引因此变成“混源 corpus”：老记忆召不回、新记忆正常。
 *
 * <p><b>为什么不做索引迁移</b>：这里的问题不是索引结构或 mapping，而是**存量向量陈旧**。
 * MySQL 是记忆的权威数据源，逐条重新 {@code indexMemory} 即可让存量与当前模型同源；
 * 该过程内部先按 memoryId 删除旧文档再写入，**幂等、可重复执行**，且不影响文章 corpus。
 * 两个 corpus 在结构上的统一（版本化 alias）留待后续，不在本次收尾范围内。
 *
 * <p><b>为什么要清幽灵文档</b>：2026-10-03 实测 ES 有 8 条 {@code episodic_memory}，而 MySQL 只有 2 条——
 * 说明历史上存在“记忆已删除、索引文档没删”的漏删。这类文档本身是漂移向量（暂时召不回），
 * 但一旦嵌入空间变化就可能被召回成“已删除的记忆”。重刷的语义既然是“以 MySQL 为准”，
 * 顺手对齐文档集合才是完整动作。**读索引失败时一律不清理**，宁可留幽灵也不误删正常记忆。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemoryRagReindexService {

    private static final String USER_MEMORY_SOURCE = "memory";
    private static final String EPISODIC_MEMORY_SOURCE = "episodic_memory";
    private static final int MAX_SCAN = 1000;

    private final AiUserMemoryMapper aiUserMemoryMapper;
    private final AiEpisodicMemoryMapper aiEpisodicMemoryMapper;
    private final AiMemoryIndexService aiMemoryIndexService;
    private final AiEpisodicMemoryIndexService aiEpisodicMemoryIndexService;
    private final ElasticsearchClient elasticsearchClient;
    private final BlogAiProperties properties;

    public ReindexOutcome reindex() {
        // 只重刷启用中的正式记忆：已停用的记忆本来就不该被召回
        List<AiUserMemories> userMemories = aiUserMemoryMapper.selectList(
                Wrappers.<AiUserMemories>lambdaQuery()
                        .eq(AiUserMemories::getEnabled, 1));
        List<AiEpisodicMemories> episodicMemories = aiEpisodicMemoryMapper.selectList(null);

        int failed = 0;
        for (AiUserMemories memory : userMemories) {
            if (!reindexOne(() -> aiMemoryIndexService.indexMemory(memory),
                    USER_MEMORY_SOURCE, memory.getId())) {
                failed++;
            }
        }
        for (AiEpisodicMemories memory : episodicMemories) {
            if (!reindexOne(() -> aiEpisodicMemoryIndexService.indexMemory(memory),
                    EPISODIC_MEMORY_SOURCE, memory.getId())) {
                failed++;
            }
        }

        int purged = purgeOrphans(USER_MEMORY_SOURCE, this::userMemoryStillActive)
                + purgeOrphans(EPISODIC_MEMORY_SOURCE, this::episodicMemoryStillExists);

        log.info("[MEMORY-REINDEX] 记忆向量重刷完成：userMemories={}, episodicMemories={}, purgedOrphans={}, failed={}",
                userMemories.size(), episodicMemories.size(), purged, failed);
        return new ReindexOutcome(userMemories.size(), episodicMemories.size(), purged, failed);
    }

    /**
     * 单条重刷 + 异常隔离。
     *
     * <p>{@code indexMemory} 内部是「先按 memoryId 删旧文档、再写入」，**不是事务**：
     * 一条失败（限流 / 超时 / 供应商异常）会中断整个循环，而失败的那条**已经删了却没重建**
     * —— MySQL 还在、ES 已无，表现就是「这条记忆再也召回不到」，且没有任何机制能发现。
     * 所以单条失败只跳过并计数，其余照常重刷；失败项重跑本接口即可补齐（幂等）。
     */
    private boolean reindexOne(Runnable action, String source, Long memoryId) {
        try {
            action.run();
            return true;
        } catch (Exception e) {
            log.warn("[MEMORY-REINDEX] 单条重刷失败已跳过，重跑本接口可补齐：source={}, memoryId={}",
                    source, memoryId, e);
            return false;
        }
    }

    /**
     * 清理“索引里有、MySQL 里没有”的记忆。
     *
     * <p>重刷是耗时动作（每条一次 embedding 调用），初始 MySQL 快照在执行期间可能已经过期：
     * 新增记忆不能误删，已删除记忆也不能被旧快照重新写回。因此 ES 中当前存在的所有 memoryId
     * 都逐条回数据库确认；候选数量本来就少，额外点查可以忽略。
     */
    private int purgeOrphans(String source, LongPredicate stillPresent) {
        Set<Long> indexedIds = loadIndexedMemoryIds(source);
        List<Long> candidates = indexedIds.stream()
                .filter(Objects::nonNull)
                .sorted()
                .toList();
        List<Long> confirmable = confirmPurgeCandidates(candidates, stillPresent);

        for (Long candidate : confirmable) {
            if (EPISODIC_MEMORY_SOURCE.equals(source)) {
                aiEpisodicMemoryIndexService.deleteMemoryIndex(candidate);
            } else {
                aiMemoryIndexService.deleteMemoryIndex(candidate);
            }
            log.warn("[MEMORY-REINDEX] 清理幽灵记忆文档：source={}, memoryId={}", source, candidate);
        }
        return confirmable.size();
    }

    /**
     * 候选孤儿里**经数据库确认已不存在**、可安全删除的部分。
     *
     * <p>纯函数，便于单测：ES 文档只是候选，真正的删除依据是删除前的数据库确认。
     */
    public static List<Long> confirmPurgeCandidates(List<Long> candidates, LongPredicate stillPresent) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        return candidates.stream()
                .filter(id -> id != null && !stillPresent.test(id))
                .toList();
    }

    private boolean userMemoryStillActive(Long memoryId) {
        AiUserMemories memory = aiUserMemoryMapper.selectById(memoryId);
        return memory != null && Integer.valueOf(1).equals(memory.getEnabled());
    }

    private boolean episodicMemoryStillExists(Long memoryId) {
        return aiEpisodicMemoryMapper.selectById(memoryId) != null;
    }

    /** 读取失败返回空集——调用方据此跳过清理，避免误删。 */
    private Set<Long> loadIndexedMemoryIds(String source) {
        try {
            SearchResponse<JsonData> response = elasticsearchClient.search(s -> s
                            .index(properties.getRag().getCorpus().getMemoryIndexName())
                            .size(MAX_SCAN)
                            .source(src -> src.filter(f -> f.includes("metadata.memoryId")))
                            .query(q -> q.term(t -> t
                                    .field("metadata.source.keyword")
                                    .value(FieldValue.of(source)))),
                    JsonData.class);

            Set<Long> ids = new LinkedHashSet<>();
            response.hits().hits().forEach(hit -> {
                JsonData document = hit.source();
                if (document == null) {
                    return;
                }
                JsonObject metadata = document.toJson().asJsonObject().getJsonObject("metadata");
                if (metadata == null || !metadata.containsKey("memoryId")) {
                    return;
                }
                ids.add(metadata.getJsonNumber("memoryId").longValue());
            });
            return ids;
        } catch (IOException | RuntimeException e) {
            log.warn("[MEMORY-REINDEX] 读取 {} 索引文档失败，本次跳过幽灵文档清理：{}", source, e.getMessage());
            return Set.of();
        }
    }

    /**
     * 重刷统计；接口返回体不引入新的前端协议。
     *
     * <p>{@code failed} = 单条重刷失败数（见 {@link #reindexOne}）。**非 0 就说明有记忆
     * 在 ES 里缺失**，重跑一次接口即可补齐——不要只看 200 就当成功。
     */
    public record ReindexOutcome(int userMemories, int episodicMemories, int purgedOrphans, int failed) {
    }
}
