package com.hailin.blogsystem.ai.rag;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldValue;
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
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
public class ArticleRagConsistencyService {

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
        try {
            SearchResponse<JsonData> response = elasticsearchClient.search(s -> s
                            .index(articleIndexName())
                            .size(1000)
                            .source(source -> source
                                    .filter(filter -> filter
                                            .includes("metadata.articleId")
                                    )
                            )
                            .query(q -> q
                                    .term(t -> t
                                            .field("metadata.source.keyword")
                                            .value(FieldValue.of("article"))
                                    )
                            ),
                    JsonData.class
            );

            Set<Long> ids = new LinkedHashSet<>();

            response.hits().hits().forEach(hit -> {
                JsonData source = hit.source();
                if (source == null) {
                    return;
                }

                Object articleId = source.toJson()
                        .asJsonObject()
                        .getJsonObject("metadata")
                        .get("articleId");

                if (articleId == null) {
                    return;
                }

                ids.add(Long.valueOf(articleId.toString()));
            });

            return ids;
        } catch (IOException e) {
            throw new IllegalStateException("读取 Elasticsearch RAG 索引失败", e);
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
