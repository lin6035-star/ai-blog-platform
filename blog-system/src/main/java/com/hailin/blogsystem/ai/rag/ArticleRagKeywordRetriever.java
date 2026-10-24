package com.hailin.blogsystem.ai.rag;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.query_dsl.Operator;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.json.JsonData;
import com.hailin.blogsystem.config.BlogAiProperties;
import com.hailin.blogsystem.entity.dto.ArticleRagContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import jakarta.json.JsonString;

/**
 * ES 词法（keyword）文本检索，作为向量检索的补充。
 *
 * <p>匹配 {@code content}，过滤 {@code metadata.source = article} 且 {@code metadata.articleStatus = 1}。
 *
 * <p><b>它返回的是「按文章摊开」的片段，而不是「分数最高的 N 个片段」</b>——这个区别是这一刀的重点。
 * chunk 级检索里同一篇文章的多个 chunk 分数接近、会**连续排在前面**，所以"取前 N 个片段"
 * 经常只覆盖一两篇文章。原始实现硬编码 {@code size=5}，实测问"有没有关于 Java 的文章"时
 * ES 里有 **51 个 chunk / 12 篇**命中，却只捞回 5 个 chunk，最终只喂给 LLM 一篇内容
 * ——用户看到的就是"只找到 1 篇，而且没讲全"。
 */
@Slf4j
@Component
public class ArticleRagKeywordRetriever {

    private final ElasticsearchClient elasticsearchClient;
    private final BlogAiProperties blogAiProperties;

    public ArticleRagKeywordRetriever(
            ElasticsearchClient elasticsearchClient,
            BlogAiProperties blogAiProperties
    ) {
        this.elasticsearchClient = elasticsearchClient;
        this.blogAiProperties = blogAiProperties;
    }

    /** 词法检索（AND 语义）：用户搜的词都得出现 */
    public List<ArticleRagContext> retrieve(String question) {
        return retrieve(question, Operator.And);
    }

    /** OR 兜底：AND 一条都没命中时才用。噪音由 {@link #pickByArticle} 的篇数 / 篇内上限兜住 */
    public List<ArticleRagContext> retrieveFallback(String question) {
        return retrieve(question, Operator.Or);
    }

    private List<ArticleRagContext> retrieve(String question, Operator operator) {
        if (question == null || question.isBlank()) {
            return List.of();
        }

        BlogAiProperties.Keyword config = blogAiProperties.getRag().getKeyword();

        try {
            SearchResponse<JsonData> response = elasticsearchClient.search(s -> s
                            .index(articleIndexName())
                            // **先多捞**：捞少了根本覆盖不到几篇（见类注释）。
                            // 捞多少、留几篇、每篇留几个，都在 blog.ai.rag.keyword 下配
                            .size(config.getFetchSize())
                            .query(q -> q
                                    .bool(b -> b
                                            .must(m -> m
                                                    /*
                                                     * **带标题权重的 multi_match**（原来是只查 content 的单字段 match）。
                                                     *
                                                     * 标题是作者自己定的主题标签，是区分"主题就是它"和
                                                     * "顺带提到了它"最可靠的信号——而只查 content 时这个信号完全用不上：
                                                     * chunker 虽然把标题前置进了 content（"标题：X"），
                                                     * 但它和正文混在同一字段里，BM25 分不出权重。
                                                     *
                                                     * 实测（2026-10-13）：正文含"缓存"的有 10 篇，标题含的只有 2 篇。
                                                     * 只查 content 时，"提及"会把"主题"挤下去——高频配角词
                                                     * （Java / 缓存 / Redis / 性能…）都会中招，而"消息队列"这类
                                                     * 专属主题词因为正文密度天然就高，把这个缺陷掩盖住了。
                                                     *
                                                     * 权重 3 倍与站内搜索（ArticleSearchService）同一口径。
                                                     */
                                                    .multiMatch(mm -> mm
                                                            .fields("metadata.title^3", "content")
                                                            .query(question.trim())
                                                            .operator(operator)
                                                    )
                                            )
                                            .filter(f -> f
                                                    .term(t -> t
                                                            .field("metadata.source.keyword")
                                                            .value(FieldValue.of("article"))
                                                    )
                                            )
                                            .filter(f -> f
                                                    .term(t -> t
                                                            .field("metadata.articleStatus")
                                                            .value(FieldValue.of(1L))
                                                    )
                                            )
                                    )
                            ),
                    JsonData.class
            );

            List<ArticleRagContext> hits = new ArrayList<>();
            response.hits().hits().forEach(hit -> {
                JsonData source = hit.source();
                if (source == null) return;

                var json = source.toJson().asJsonObject();
                var meta = json.getJsonObject("metadata");
                if (meta == null) return;

                Long articleId = toLong(meta.get("articleId"));
                String title = toString(meta.get("title"));
                Integer chunkIndex = toInt(meta.get("chunkIndex"));
                String content = meta.get("rawContent") instanceof JsonString raw
                        ? raw.getString()
                        : json.get("content") instanceof JsonString js ? js.getString() : "";

                hits.add(new ArticleRagContext(articleId, title, chunkIndex, content));
            });

            List<ArticleRagContext> picked = pickByArticle(hits, config);
            log.info("RAG keyword 检索完成，operator={}, question={}, 命中chunk={}, 覆盖文章={}, 返回chunk={}",
                    operator, question, hits.size(), countArticles(hits), picked.size());
            return picked;
        } catch (IOException e) {
            log.error("RAG keyword 检索失败，question={}", question, e);
            return List.of();
        }
    }

    /**
     * 把 chunk 级命中**按文章摊开**：每篇最多 {@code chunksPerArticle} 个，总共最多
     * {@code maxArticles} 篇。
     *
     * <p>ES 按分数降序返回，所以**每篇文章第一次出现的那条就是它最相关的片段**——
     * 一次遍历同时完成"挑出该篇最佳片段"和"按篇的相关度排序"两件事。
     *
     * <p>篇数满了之后**不再接纳新文章**，但已入选的文章仍可把额度补满——
     * 否则会出现"一篇只占了 1 个名额、另一篇的第二个片段被白白丢掉"。
     *
     * <p>没有 {@code articleId} 的片段直接跳过：它归不了组，也就参与不了"覆盖 N 篇"这个语义。
     *
     * <p>包级可见（不是 private）是为了让单测直接喂数据——它是纯函数，不需要起 ES。
     */
    static List<ArticleRagContext> pickByArticle(List<ArticleRagContext> hits,
                                                 BlogAiProperties.Keyword config) {
        if (hits == null || hits.isEmpty()) {
            return List.of();
        }

        int maxArticles = Math.max(1, config.getMaxArticles());
        int perArticleLimit = Math.max(1, config.getChunksPerArticle());

        Map<Long, Integer> takenPerArticle = new LinkedHashMap<>();
        List<ArticleRagContext> picked = new ArrayList<>();

        for (ArticleRagContext hit : hits) {
            Long articleId = hit.articleId();
            if (articleId == null) {
                continue;
            }
            int taken = takenPerArticle.getOrDefault(articleId, 0);
            if (taken >= perArticleLimit) {
                continue;
            }
            if (taken == 0 && takenPerArticle.size() >= maxArticles) {
                continue;
            }
            takenPerArticle.put(articleId, taken + 1);
            picked.add(hit);
        }
        return List.copyOf(picked);
    }

    private static long countArticles(List<ArticleRagContext> hits) {
        return hits.stream()
                .map(ArticleRagContext::articleId)
                .filter(Objects::nonNull)
                .distinct()
                .count();
    }

    private Long toLong(Object value) {
        if (value == null) return null;
        if (value instanceof Number n) return n.longValue();
        return Long.valueOf(value.toString());
    }

    private Integer toInt(Object value) {
        if (value == null) return null;
        if (value instanceof Number n) return n.intValue();
        return Integer.valueOf(value.toString());
    }

    private String toString(Object value) {
        return value == null ? "" : value.toString();
    }

    private String articleIndexName() {
        return blogAiProperties.getRag().getCorpus().getArticleIndexName();
    }
}
