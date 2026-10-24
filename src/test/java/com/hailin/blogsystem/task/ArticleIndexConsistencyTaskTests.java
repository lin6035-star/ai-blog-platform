package com.hailin.blogsystem.task;

import com.hailin.blogsystem.ai.rag.ArticleRagConsistencyService;
import com.hailin.blogsystem.entity.vo.ArticleRagConsistencyReportVO;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ArticleIndexConsistencyTaskTests {

    private final ArticleRagConsistencyService consistencyService = mock(ArticleRagConsistencyService.class);
    private final ArticleIndexConsistencyTask task = new ArticleIndexConsistencyTask(consistencyService);

    @Test
    void logsBothIndexesEvenWhenRagCheckFails() {
        when(consistencyService.check()).thenThrow(new IllegalStateException("rag index missing"));
        when(consistencyService.checkSearchIndex()).thenReturn(
                new ArticleRagConsistencyService.SearchIndexReport(1, 1, List.of(), List.of(), LocalDateTime.now()));

        task.reconcile();

        // RAG 对账失败不能把搜索对账一起跳过
        verify(consistencyService).check();
        verify(consistencyService).checkSearchIndex();
    }

    @Test
    void toleratesDifferenceInBothIndexesWithoutThrowing() {
        when(consistencyService.check()).thenReturn(ArticleRagConsistencyReportVO.builder()
                .publishedArticleCount(3L)
                .indexedArticleCount(2L)
                .missingInEs(List.of(1L))
                .extraInEs(List.of(9L))
                .checkedAt(LocalDateTime.now())
                .build());
        when(consistencyService.checkSearchIndex()).thenReturn(
                new ArticleRagConsistencyService.SearchIndexReport(
                        3, 2, List.of(1L), List.of(9L), LocalDateTime.now()));

        task.reconcile();

        verify(consistencyService).check();
        verify(consistencyService).checkSearchIndex();
    }
}
