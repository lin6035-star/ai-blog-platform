package com.hailin.blogsystem.ai.rag;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.aggregations.CompositeAggregate;
import co.elastic.clients.elasticsearch._types.aggregations.CompositeAggregationSource;
import co.elastic.clients.elasticsearch._types.aggregations.CompositeBucket;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.json.JsonData;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hailin.blogsystem.config.BlogAiProperties;
import com.hailin.blogsystem.ai.rag.index.ArticleRagIndexHealthService;
import com.hailin.blogsystem.constants.BlogConstants;
import com.hailin.blogsystem.entity.Articles;
import com.hailin.blogsystem.entity.dto.ArticleRagRepairResult;
import com.hailin.blogsystem.entity.vo.ArticleRagConsistencyReportVO;
import com.hailin.blogsystem.mapper.ArticlesMapper;
import com.hailin.blogsystem.exception.BusinessException;
import com.hailin.blogsystem.search.ArticleSearchService;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class ArticleRagConsistencyService {

    /** composite 聚合单页桶数：装不下的文章 ID 靠 after_key 翻页，不再有 1000 条上限 */
    private static final int COMPOSITE_PAGE_SIZE = 1000;
    private static final String ARTICLE_IDS_AGG = "article_ids";
    private static final String RAG_ARTICLE_ID_FIELD = "metadata.articleId";
    private static final String SEARCH_ARTICLE_ID_FIELD = "articleId";

    private final ArticlesMapper articlesMapper;
    private final ElasticsearchClient elasticsearchClient;
    private final ArticleRagIndexService articleRagIndexService;
    private final BlogAiProperties blogAiProperties;
    private final ArticleRagIndexHealthService healthService;

    public ArticleRagConsistencyService(
            ArticlesMapper articlesMapper,
            ElasticsearchClient elasticsearchClient,
            ArticleRagIndexService articleRagIndexService,
            BlogAiProperties blogAiProperties,
            ArticleRagIndexHealthService healthService
    ) {
        this.articlesMapper = articlesMapper;
        this.elasticsearchClient = elasticsearchClient;
        this.articleRagIndexService = articleRagIndexService;
        this.blogAiProperties = blogAiProperties;
        this.healthService = healthService;
    }

    public ArticleRagConsistencyReportVO check(){
        Set<Long> publishedArticleIds = loadPublishedArticleIds();
        Set<Long> indexedArticleIds = loadIndexedArticleIds();

        List<Long> missingInEs = publishedArticleIds.stream()
                .filter(id -> !indexedArticleIds.contains(id))
                .toList();

        List<Long> extraInEs = indexedArticleIds.stream()
                .filter(id -> !publishedArticleIds.contains(id))
                .toList();

        return ArticleRagConsistencyReportVO.builder()
                .publishedArticleCount((long) publishedArticleIds.size())
                .indexedArticleCount((long) indexedArticleIds.size())
                .missingInEs(missingInEs)
                .extraInEs(extraInEs)
                .checkedAt(LocalDateTime.now())
                .build();
    }

    private Set<Long> loadPublishedArticleIds(){
        List<Articles> articles = articlesMapper.selectList(
                Wrappers.lambdaQuery(Articles.class)
                        .select(Articles::getId)
                        .eq(Articles::getStatus, BlogConstants.ArticlesStatus.PUBLISHED)
        );

        Set<Long> ids = new LinkedHashSet<>();
        for(Articles article : articles){
            if (article.getId() != null) {
                ids.add(article.getId());
            }
        }

        return ids;
    }

    private Set<Long> loadIndexedArticleIds() {
        return loadArticleIdsFromIndex(articleIndexName(), "article", RAG_ARTICLE_ID_FIELD);
    }

    /**
     * 站内搜索索引对账（v1 只读）：
     * 现有 {@link #check()} 只比较「DB 已发布文章 vs RAG 向量索引」，完全不看 {@code article_search_index}；
     * 这里补上同一口径的搜索侧检查，由定时任务一起输出。
     *
     * <p>搜索索引文档 {@code _id} = articleId、文档里有 {@code articleId}(long)，所以用 composite 聚合
     * 按 articleId 去重即可；不假设「索引里只有已发布文章」——多出来的会进 extra，交给人工/重建处理。</p>
     */
    public SearchIndexReport checkSearchIndex() {
        Set<Long> publishedArticleIds = loadPublishedArticleIds();
        Set<Long> indexedArticleIds = loadArticleIdsFromIndex(
                ArticleSearchService.INDEX_NAME, null, SEARCH_ARTICLE_ID_FIELD);

        List<Long> missingInEs = publishedArticleIds.stream()
                .filter(id -> !indexedArticleIds.contains(id))
                .toList();
        List<Long> extraInEs = indexedArticleIds.stream()
                .filter(id -> !publishedArticleIds.contains(id))
                .toList();

        return new SearchIndexReport(
                publishedArticleIds.size(),
                indexedArticleIds.size(),
                missingInEs,
                extraInEs,
                LocalDateTime.now()
        );
    }

    /**
     * 用 composite 聚合 + after_key 翻页收集文章 ID。
     *
     * <p>原实现 {@code .size(1000)} 拉桶是 **chunk 文档**：文章/片段超过 1000 后，
     * 后面的文章 ID 根本不会出现在结果里，对账会误报「缺失/多余」。composite 聚合按 articleId
     * 去重、且没有 1000 条限制，天然适合做对账。</p>
     */
    private Set<Long> loadArticleIdsFromIndex(
            String indexName,
            String sourceFilter,
            String articleIdField
    ) {
        Set<Long> ids = new LinkedHashSet<>();
        Map<String, FieldValue> afterKey = null;
        try {
            do {
                final Map<String, FieldValue> after = afterKey;
                SearchResponse<JsonData> response = elasticsearchClient.search(s -> {
                    s.index(indexName)
                            .size(0)
                            .aggregations(ARTICLE_IDS_AGG, a -> a.composite(c -> {
                                c.size(COMPOSITE_PAGE_SIZE)
                                        .sources(List.of(Map.of(
                                                "article",
                                                CompositeAggregationSource.of(src -> src.terms(
                                                        t -> t.field(articleIdField)))
                                        )));
                                if (after != null && !after.isEmpty()) {
                                    c.after(after);
                                }
                                return c;
                            }));
                    if (sourceFilter != null) {
                        s.query(q -> q.term(t -> t
                                .field("metadata.source.keyword")
                                .value(FieldValue.of(sourceFilter))));
                    }
                    return s;
                }, JsonData.class);

                CompositeAggregate aggregate = response.aggregations()
                        .get(ARTICLE_IDS_AGG)
                        .composite();
                for (CompositeBucket bucket : aggregate.buckets().array()) {
                    FieldValue value = bucket.key().get("article");
                    if (value != null && value.isLong()) {
                        ids.add(value.longValue());
                    }
                }
                afterKey = aggregate.afterKey();
            } while (afterKey != null && !afterKey.isEmpty());

            return ids;
        } catch (IOException e) {
            throw new IllegalStateException("读取 Elasticsearch 索引失败: " + indexName, e);
        }
    }

    /** 站内搜索索引对账结果（字段语义与 RAG 报告一致）。 */
    public record SearchIndexReport(
            long publishedArticleCount,
            long indexedArticleCount,
            List<Long> missingInEs,
            List<Long> extraInEs,
            LocalDateTime checkedAt
    ) {
        public boolean consistent() {
            return missingInEs.isEmpty() && extraInEs.isEmpty();
        }
    }

    public ArticleRagRepairResult repair() {
        if (!healthService.incrementalWritesAllowed()) {
            ArticleRagIndexHealthService.Snapshot snapshot = healthService.current();
            throw new BusinessException(
                    BlogConstants.ErrorCode.CONFLICT,
                    "文章 RAG 索引不健康，禁止局部修复，请执行隔离全量重建: " + snapshot.reason()
            );
        }
        ArticleRagConsistencyReportVO report = check();

        int missingFixed = 0;
        int extraDeleted = 0;
        int chunkReindexed = 0;

        for (Long articleId : report.getMissingInEs()) {
            int indexedChunks = articleRagIndexService.indexArticle(articleId);
            if (indexedChunks > 0) {
                missingFixed++;
                chunkReindexed += indexedChunks;
            }
        }

        for (Long articleId : report.getExtraInEs()) {
            articleRagIndexService.deleteArticleIndex(articleId);
            extraDeleted++;
        }

        return new ArticleRagRepairResult(missingFixed, extraDeleted, chunkReindexed);
    }

    private String articleIndexName() {
        return blogAiProperties.getRag().getCorpus().getArticleIndexName();
    }
}
