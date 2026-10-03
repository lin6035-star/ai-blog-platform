package com.hailin.blogsystem.ai.rag;

import com.hailin.blogsystem.config.AiVectorStoreConfig;
import com.hailin.blogsystem.config.BlogAiProperties;
import com.hailin.blogsystem.ai.rag.index.ArticleRagIndexHealthService;
import com.hailin.blogsystem.entity.dto.ArticleRagContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import org.springframework.ai.document.Document;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
//检索服务
public class ArticleRagRetrieveService {

    private final BlogAiProperties blogAiProperties;

    private final VectorStore vectorStore;
    private final ArticleRagIndexHealthService healthService;

    public ArticleRagRetrieveService(
            BlogAiProperties blogAiProperties,
            @Qualifier(AiVectorStoreConfig.ARTICLE_RAG_VECTOR_STORE) VectorStore vectorStore,
            ArticleRagIndexHealthService healthService
    ) {
        this.blogAiProperties = blogAiProperties;
        this.vectorStore = vectorStore;
        this.healthService = healthService;
    }

    public List<ArticleRagContext> retrieve(String question){
        if (question == null || question.isBlank()) {
            return List.of();
        }
        if (!healthService.denseSearchAllowed()) {
            ArticleRagIndexHealthService.Snapshot snapshot = healthService.current();
            log.warn("RAG dense 检索降级，indexState={}，reason={}", snapshot.state(), snapshot.reason());
            return List.of();
        }

        BlogAiProperties.Rag rag = blogAiProperties.getRag();

        log.info(
                "RAG 检索开始，question={}, topK={}, similarityThreshold={}",
                question,
                rag.getTopK(),
                rag.getSimilarityThreshold()
        );

        List<Document> documents = vectorStore.similaritySearch(
                SearchRequest.builder()
                        .query(question.trim())  //用户问题
                        .topK(blogAiProperties.getRag().getTopK())  //返回最相似的五个
                        .similarityThreshold(blogAiProperties.getRag().getSimilarityThreshold())  //相似度阈值（当前不过滤）
                        .filterExpression(articleSourceFilter())
                        .build()
        );

        if (documents == null || documents.isEmpty()) {
            log.info("RAG 检索结束，未召回片段");
            return List.of();
        }

        //过滤出 source="article" 的文档
        //转换成 ArticleRagContext DTO
        List<ArticleRagContext> contexts = documents.stream()
                .filter(this::isArticleDocument)
                .map(this::toArticleRagContext)
                .toList();

        log.info("RAG 检索结束，召回片段数={}", contexts.size());

        //增强一下后端RAG日志
        for (int i = 0; i < contexts.size(); i++) {
            ArticleRagContext context = contexts.get(i);
            log.info(
                    "RAG 召回片段 {}，articleId={}, title={}, chunkIndex={}",
                    i + 1,
                    context.articleId(),
                    context.title(),
                    context.chunkIndex()
            );
        }

        return contexts;

    }

    private Filter.Expression articleSourceFilter() {
        return new Filter.Expression(
                Filter.ExpressionType.AND,
                new Filter.Expression(
                        Filter.ExpressionType.EQ,
                        new Filter.Key("source"),
                        new Filter.Value("article")
                ),
                new Filter.Expression(
                        Filter.ExpressionType.EQ,
                        new Filter.Key("articleStatus"),
                        new Filter.Value(1)
                )
        );
    }

    private boolean isArticleDocument(Document document){
        Object source = document.getMetadata().get("source");
        return "article".equals(source);
    }

    private ArticleRagContext toArticleRagContext(Document document){
        Map<String, Object> metadata = document.getMetadata();

        return new ArticleRagContext(
                toLong(metadata.get("articleId")),
                toStringValue(metadata.get("title")),
                toInteger(metadata.get("chunkIndex")),
                metadata.get("rawContent") == null
                        ? document.getText()
                        : toStringValue(metadata.get("rawContent"))
        );
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

    private String toStringValue(Object value) {
        return value == null ? "" : value.toString();
    }

}
