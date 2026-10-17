package com.hailin.blogsystem.ai.memory;

import com.hailin.blogsystem.config.AiVectorStoreConfig;
import com.hailin.blogsystem.entity.dto.EpisodicMemoryExtractResult;
import com.hailin.blogsystem.entity.dto.EpisodicMemoryRagContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class AiEpisodicMemoryRetrieveService {

    private static final int PROMPT_TOP_K = 5;
    private static final int PROMPT_LIMIT = 3;
    private static final int DEDUPE_TOP_K = 3;

    /**
     * 提示召回阈值。
     *
     * <p><b>2026-10-16 从 0.58 降到 0.50</b>。实测（线上同一 embedding 模型 + ES knn 复现）：
     * 用户问「还记得我们之前决定的，RAG使用什么方法吗」时，命中那条情景记忆的 cosine = 0.5607
     * ——**差 0.019 被挡掉**，AI 转而答"记忆中没有记录"，而那条记忆就在库里。
     *
     * <p>更硬的证据：用 `ai_agent_steps` 里 18 条**真实线上检索词**跑阈值敏感性，
     * <b>0.58 下 0/18 能召回</b>（全库最高分仅 0.5717）、0.55 下 2/18、0.50 下 5/18。
     * 也就是说 0.58 不是"偶尔漏"，是<b>让这条链路几乎从未生效过</b>。
     * ⚠️ 别把它当成"有意保守"改回去：0.58 是 V1 实现计划里直接写死的常量
     * （设计稿从未讨论过检索阈值），且同期文章 RAG 的 0.58 是 rerank Gate 的实测标定值，
     * 场景不同（有大量候选要精排）；记忆召回候选极少，漏召回的代价远大于误召回。
     *
     * <p>误召回验证：无关问题（"帮我写一篇 Redis 缓存穿透的文章" 0.3294 / "今天心情不错" 0.3228 /
     * "我的博客项目下一步该做什么" 0.3409）最高 0.3409，离 0.50 有 0.16 余量；
     * 0.50 相对 0.58 新增的 3 条召回全是"问背景/个人情况"命中身份类记忆，属合理召回。
     *
     * <p>另一条依据是与长期记忆对齐：{@link AiMemoryRetrieveService} 的阈值本就是 0.50，
     * 情景记忆反而更严没有道理——而情景记忆**只有向量这一条通道**（长期记忆还有常驻通道兜底），
     * 阈值每严一分都是直接静默。
     *
     * <p>样本限制：2 条情景记忆 / 18 条检索词 / 1 个账号，方向明确但样本小；
     * 记忆量上来后应重新标定，而不是把 0.50 当永久值。
     */
    private static final double PROMPT_SIMILARITY_THRESHOLD = 0.50;
    private static final double DEDUPE_SIMILARITY_THRESHOLD = 0.85;

    private final VectorStore vectorStore;

    public AiEpisodicMemoryRetrieveService(
            @Qualifier(AiVectorStoreConfig.MEMORY_VECTOR_STORE) VectorStore vectorStore
    ) {
        this.vectorStore = vectorStore;
    }

    public List<EpisodicMemoryRagContext> retrieveForPrompt(Long userId,
                                                                                        String projectKey, String question){
        if (userId == null || projectKey == null || projectKey.isBlank()
                || question == null || question.isBlank()) {
            return List.of();
        }

        try{
            List<Document> documents = vectorStore.similaritySearch(
                    SearchRequest.builder()
                            .query(question.trim())
                            .topK(PROMPT_TOP_K)
                            .similarityThreshold(PROMPT_SIMILARITY_THRESHOLD)
                            .filterExpression(episodicFilter(userId, projectKey))
                            .build()
            );

            if (documents == null || documents.isEmpty()) {
                log.info("Episodic Memory 召回完成，userId={}, projectKey={}, count=0（阈值 {} 下无命中）",
                        userId, projectKey, PROMPT_SIMILARITY_THRESHOLD);
                return List.of();
            }

            List<EpisodicMemoryRagContext> contexts = documents.stream()
                    .filter(this::isEpisodicDocument)
                    .filter(document -> userId.equals(toLong(document.getMetadata().get("userId"))))
                    .filter(document -> projectKey.equals(toStringValue(document.getMetadata().get("projectKey"))))
                    .map(this::toContext)
                    .sorted(Comparator.comparingInt(this::importanceOrDefault).reversed())
                    .limit(PROMPT_LIMIT)
                    .toList();

            log.info("Episodic Memory 召回完成，userId={}, projectKey={}, count={}",
                    userId, projectKey, contexts.size());

            return contexts;

        } catch (Exception e) {
            log.warn("Episodic Memory 召回失败，userId={}, projectKey={}", userId, projectKey, e);
            return List.of();
        }
    }

    public List<EpisodicMemoryRagContext> retrieveForDedupe(Long userId, String projectKey, String content){
        if (userId == null || projectKey == null || projectKey.isBlank()
                || content == null || content.isBlank()) {
            return List.of();
        }

        try {
            List<Document> documents = vectorStore.similaritySearch(
                    SearchRequest.builder()
                            .query(content.trim())
                            .topK(DEDUPE_TOP_K)
                            .similarityThreshold(DEDUPE_SIMILARITY_THRESHOLD)
                            .filterExpression(episodicFilter(userId, projectKey))
                            .build()
            );

            if (documents == null || documents.isEmpty()) {
                return List.of();
            }

            return documents.stream()
                    .filter(this::isEpisodicDocument)
                    .filter(document -> userId.equals(toLong(document.getMetadata().get("userId"))))
                    .filter(document -> projectKey.equals(toStringValue(document.getMetadata().get("projectKey"))))
                    .map(this::toContext)
                    .toList();
        } catch (Exception e) {
            log.warn("Episodic Memory 去重召回失败，userId={}, projectKey={}", userId, projectKey, e);
            return List.of();
        }

    }

    private Filter.Expression episodicFilter(Long userId, String projectKey) {
        return new Filter.Expression(
                Filter.ExpressionType.AND,
                new Filter.Expression(
                        Filter.ExpressionType.AND,
                        new Filter.Expression(
                                Filter.ExpressionType.EQ,
                                new Filter.Key("source"),
                                new Filter.Value(AiEpisodicMemoryIndexService.SOURCE_EPISODIC_MEMORY)
                        ),
                        new Filter.Expression(
                                Filter.ExpressionType.EQ,
                                new Filter.Key("userId"),
                                new Filter.Value(userId)
                        )
                ),
                new Filter.Expression(
                        Filter.ExpressionType.EQ,
                        new Filter.Key("projectKey"),
                        new Filter.Value(projectKey)
                )
        );
    }

    private boolean isEpisodicDocument(Document document) {
        return document != null
                && AiEpisodicMemoryIndexService.SOURCE_EPISODIC_MEMORY
                .equals(document.getMetadata().get("source"));
    }

    private EpisodicMemoryRagContext toContext(Document document) {
        Map<String, Object> metadata = document.getMetadata();

        return new EpisodicMemoryRagContext(
                toLong(metadata.get("memoryId")),
                toLong(metadata.get("userId")),
                toStringValue(metadata.get("projectKey")),
                toStringValue(metadata.get("memoryType")),
                toStringValue(metadata.get("title")),
                document.getText(),
                toBigDecimal(metadata.get("confidence")),
                toInteger(metadata.get("importance")),
                toLocalDateTime(metadata.get("occurredAt"))
        );
    }

    private int importanceOrDefault(EpisodicMemoryRagContext context) {
        return context.importance() == null ? 6 : context.importance();
    }

    private Long toLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            return Long.valueOf(text);
        }
        return null;
    }

    private Integer toInteger(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            return Integer.valueOf(text);
        }
        return null;
    }

    private BigDecimal toBigDecimal(Object value) {
        if (value instanceof BigDecimal number) {
            return number;
        }
        if (value instanceof Number number) {
            return BigDecimal.valueOf(number.doubleValue());
        }
        if (value instanceof String text && !text.isBlank()) {
            return new BigDecimal(text);
        }
        return new BigDecimal("0.80");
    }

    private LocalDateTime toLocalDateTime(Object value) {
        if (value instanceof LocalDateTime time) {
            return time;
        }
        if (value instanceof String text && !text.isBlank()) {
            return LocalDateTime.parse(text);
        }
        return null;
    }

    private String toStringValue(Object value) {
        return value == null ? "" : value.toString();
    }

}
