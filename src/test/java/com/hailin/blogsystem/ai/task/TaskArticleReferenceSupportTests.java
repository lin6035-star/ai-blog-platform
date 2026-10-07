package com.hailin.blogsystem.ai.task;

import com.hailin.blogsystem.entity.vo.ArticleRagReferenceVO;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TaskArticleReferenceSupportTests {

    @Test
    void convertsSearchEvidenceToDeduplicatedReferences() {
        StepResult first = StepResult.applied("s1", "找到文章", Map.of(
                "articles", List.of(
                        Map.of("articleId", "101", "title", "Redis 缓存", "summary", "缓存一致性与高并发治理"),
                        Map.of("articleId", "102", "title", "Redis 秒杀", "summary", "库存预扣与异步链路"))));
        StepResult second = StepResult.applied("s2", "再次找到文章", Map.of(
                "articles", List.of(
                        Map.of("articleId", "101", "title", "重复结果", "summary", "不应覆盖第一次命中"),
                        Map.of("articleId", "bad-id", "title", "非法结果", "summary", "忽略"),
                        Map.of("title", "缺少 ID", "summary", "忽略"))));

        List<ArticleRagReferenceVO> references = TaskArticleReferenceSupport.fromResults(List.of(first, second));

        assertThat(references).extracting(ArticleRagReferenceVO::getArticleId)
                .containsExactly(101L, 102L);
        assertThat(references).extracting(ArticleRagReferenceVO::getTitle)
                .containsExactly("Redis 缓存", "Redis 秒杀");
        assertThat(references).extracting(ArticleRagReferenceVO::getSnippet)
                .containsExactly("缓存一致性与高并发治理", "库存预扣与异步链路");
        assertThat(references).extracting(ArticleRagReferenceVO::getChunkIndex)
                .containsOnlyNulls();
    }

    @Test
    void missingSearchEvidenceProducesNoReferences() {
        StepResult result = StepResult.applied("s1", "点赞成功", Map.of("articleId", "101"));

        assertThat(TaskArticleReferenceSupport.fromResults(List.of(result))).isEmpty();
        assertThat(TaskArticleReferenceSupport.fromResults(null)).isEmpty();
    }
}
