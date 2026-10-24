package com.hailin.blogsystem.ai.rag.index;

import com.hailin.blogsystem.config.BlogAiProperties;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.stereotype.Component;

import java.util.List;

/** 固定 canary 用于识别运行期 embedding 空间漂移。 */
@Component
public class ArticleRagCanary {

    public static final String DOCUMENT_ID = "article-rag-canary-v1";
    public static final String SOURCE = "article_rag_canary";
    public static final String TEXT = "文章检索向量空间健康检查：固定哨兵文本，不承载业务内容。";

    private final BlogAiProperties properties;

    public ArticleRagCanary(BlogAiProperties properties) {
        this.properties = properties;
    }

    public void writeTo(VectorStore target) {
        target.add(List.of(Document.builder()
                .id(DOCUMENT_ID)
                .text(TEXT)
                .metadata("source", SOURCE)
                .build()));
    }

    public ProbeResult probe(VectorStore target) {
        List<Document> matches = target.similaritySearch(SearchRequest.builder()
                .query(TEXT)
                .topK(1)
                .similarityThreshold(0.0D)
                .filterExpression(new Filter.Expression(
                        Filter.ExpressionType.EQ,
                        new Filter.Key("source"),
                        new Filter.Value(SOURCE)
                ))
                .build());

        if (matches == null || matches.isEmpty()) {
            return new ProbeResult(false, null, "canary 文档不存在或无法召回");
        }

        Document match = matches.get(0);
        Double score = match.getScore();
        if (!DOCUMENT_ID.equals(match.getId())) {
            return new ProbeResult(false, score, "canary top1 文档不匹配: " + match.getId());
        }
        if (score == null) {
            return new ProbeResult(false, null, "canary 未返回原始 cosine");
        }

        double threshold = properties.getRag().getHealth().getCanaryThreshold();
        if (score < threshold) {
            return new ProbeResult(false, score,
                    "canary cosine=" + score + " 低于阈值 " + threshold);
        }
        return new ProbeResult(true, score, "OK");
    }

    public record ProbeResult(boolean healthy, Double score, String reason) {
    }
}
