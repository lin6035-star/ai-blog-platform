package com.hailin.blogsystem.ai.rag;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hailin.blogsystem.constants.BlogConstants;
import com.hailin.blogsystem.config.AiVectorStoreConfig;
import com.hailin.blogsystem.entity.Articles;
import com.hailin.blogsystem.mapper.ArticlesMapper;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/*
*   发布文章 -> indexArticle(id)
    更新文章 -> deleteArticleIndex(id) + indexArticle(id)
    隐藏文章 -> deleteArticleIndex(id)
    删除文章 -> deleteArticleIndex(id)
* */

@Service
//索引服务
//ArticleRagIndexService = 真正干活：切片、embedding、写 ES
//ArticleRagSyncService = 调度入口：现在用 @Async，以后换 MQ
//ArticlesServiceImpl = 只触发同步，不关心具体怎么同步

public class ArticleRagIndexService {

    /** 阿里云 DashScope Embedding API 单次请求的批量上限 */
    private static final int EMBEDDING_BATCH_SIZE = 10;

    private final ArticlesMapper articlesMapper;
    private final ArticleRagChunker articleRagChunker;
    private final VectorStore vectorStore;

    public ArticleRagIndexService(
            ArticlesMapper articlesMapper,
            ArticleRagChunker articleRagChunker,
            @Qualifier(AiVectorStoreConfig.ARTICLE_RAG_VECTOR_STORE) VectorStore vectorStore
    ) {
        this.articlesMapper = articlesMapper;
        this.articleRagChunker = articleRagChunker;
        this.vectorStore = vectorStore;
    }

    /**
     * 将当前已发布文章写入指定 corpus，不执行删除操作。
     * 供隔离重建使用，调用方负责在校验通过后切换 alias。
     */
    public int indexPublishedArticles(VectorStore targetVectorStore) {
        if (targetVectorStore == null) {
            throw new IllegalArgumentException("targetVectorStore 不能为空");
        }

        List<Articles> articles = articlesMapper.selectList(
                Wrappers.lambdaQuery(Articles.class)
                        .select(
                                Articles::getId,
                                Articles::getTitle,
                                Articles::getSummary,
                                Articles::getContent,
                                Articles::getStatus
                        )
                        .eq(Articles::getStatus, BlogConstants.ArticlesStatus.PUBLISHED)
        );

        List<Document> documents = new ArrayList<>();
        for (Articles article : articles) {
            documents.addAll(buildArticleDocuments(article));
        }
        addDocumentsInBatches(targetVectorStore, documents);
        return documents.size();
    }

    private String buildDocumentId(Long articleId, int chunkIndex) {
        return "article:" + articleId + ":chunk:" + chunkIndex;
    }

    public int indexArticle(Long articleId){
        if (articleId == null) {
            return 0;
        }

        Articles article = articlesMapper.selectOne(
                Wrappers.lambdaQuery(Articles.class)
                        .select(
                                Articles::getId,
                                Articles::getTitle,
                                Articles::getSummary,
                                Articles::getContent,
                                Articles::getStatus
                        )
                        .eq(Articles::getId, articleId)
        );

        if (article == null || !article.getStatus().equals(BlogConstants.ArticlesStatus.PUBLISHED)) {
            return 0;
        }

        deleteArticleIndex(articleId);

        List<Document> documents = buildArticleDocuments(article);

        addDocumentsInBatches(documents);

        return documents.size();

    }

    public void deleteArticleIndex(Long articleId){
        if(articleId == null){
            return;
        }

        vectorStore.delete(new Filter.Expression(
                Filter.ExpressionType.EQ,
                new Filter.Key("articleId"),
                new Filter.Value(articleId)
        ));

    }

    private void addDocumentsInBatches(List<Document> documents) {
        addDocumentsInBatches(vectorStore, documents);
    }

    private void addDocumentsInBatches(VectorStore targetVectorStore, List<Document> documents) {
        if (documents.isEmpty()) {
            return;
        }

        for (int i = 0; i < documents.size(); i += EMBEDDING_BATCH_SIZE) {
            int end = Math.min(i + EMBEDDING_BATCH_SIZE, documents.size());
            targetVectorStore.add(documents.subList(i, end));
        }
    }

    private List<Document> buildArticleDocuments(Articles article){
        //给每个文章切块
        List<String> chunks = articleRagChunker.chunk(
                article.getTitle(),
                article.getSummary(),
                article.getContent()
        );

        List<Document> documents = new ArrayList<>();

        //每块包装成SpringAI 的Document，带上元数据
        for(int i = 0; i< chunks.size(); i++){
            Document document = Document.builder()
                    .id(buildDocumentId(article.getId(),i))
                    .text(chunks.get(i))
                    .metadata("source","article")  //来源标记
                    .metadata("articleId",article.getId())
                    .metadata("title",article.getTitle())
                    .metadata("headingPath", "")
                    .metadata("rawContent", chunks.get(i))
                    .metadata("articleStatus", BlogConstants.ArticlesStatus.PUBLISHED)
                    .metadata("chunkIndex",i)  //第几个片段
                    .build();

            documents.add(document);
        }

        return documents;
    }
}
