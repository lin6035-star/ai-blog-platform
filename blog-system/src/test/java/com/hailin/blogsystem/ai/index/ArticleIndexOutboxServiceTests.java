package com.hailin.blogsystem.ai.index;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.hailin.blogsystem.mapper.ArticleIndexOutboxMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ArticleIndexOutboxServiceTests {

    /**
     * 纯单测没有 Spring/MyBatis 上下文，Lambda 条件构造器拿不到实体元数据会抛
     * "can not find lambda cache for this entity"；手动初始化一次 TableInfo 即可。
     */
    @BeforeAll
    static void initTableInfo() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, ""), ArticleIndexOutbox.class);
    }

    private final ArticleIndexOutboxMapper mapper = mock(ArticleIndexOutboxMapper.class);
    private final ArticleIndexOutboxService service = new ArticleIndexOutboxService(mapper);

    /** enqueue 必须和文章写库同事务；不在事务里调用要立刻炸，不能静默退化成"先写库后投递"。 */
    @Test
    void enqueueWithoutTransactionFailsFast() {
        assertThatThrownBy(() -> service.enqueue(1L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("事务");
        verify(mapper, never()).insert(any(ArticleIndexOutbox.class));
    }

    @Test
    void claimDueReturnsOnlyRowsWonByCas() {
        ArticleIndexOutbox first = row(1L, 11L, 0);
        ArticleIndexOutbox second = row(2L, 12L, 0);
        when(mapper.selectList(any())).thenReturn(List.of(first, second));
        when(mapper.update(isNull(), any())).thenReturn(1, 0);

        List<ArticleIndexOutbox> claimed = service.claimDue(10, 60);

        assertThat(claimed).extracting(ArticleIndexOutbox::getId).containsExactly(1L);
        assertThat(claimed.get(0).getStatus()).isEqualTo(ArticleIndexOutbox.STATUS_SENDING);
        assertThat(claimed.get(0).getLockedUntil()).isNotNull();
    }

    @Test
    void enqueueDeferredInsertsPendingRowForLaterRetry() {
        LocalDateTime before = LocalDateTime.now();

        service.enqueueDeferred(15L, 30);

        ArgumentCaptor<ArticleIndexOutbox> captor = ArgumentCaptor.forClass(ArticleIndexOutbox.class);
        verify(mapper).insert(captor.capture());
        ArticleIndexOutbox row = captor.getValue();
        assertThat(row.getStatus()).isEqualTo(ArticleIndexOutbox.STATUS_PENDING);
        assertThat(row.getArticleId()).isEqualTo(15L);
        assertThat(row.getNextRetryAt()).isAfter(before.plusSeconds(20));
    }

    @Test
    void sendFailureGoesBackToPendingWithBackoff() {
        ArticleIndexOutbox row = row(7L, 70L, 0);

        service.markSendFailed(row, "broker down", 20);

        LambdaUpdateWrapper<ArticleIndexOutbox> update = captureUpdate();
        assertThat(update.getParamNameValuePairs().values())
                .contains(ArticleIndexOutbox.STATUS_PENDING, 1);
        assertThat(update.getParamNameValuePairs().values())
                .anySatisfy(value -> assertThat(value).isInstanceOf(LocalDateTime.class));
    }

    @Test
    void sendFailureAfterMaxAttemptsBecomesFailed() {
        ArticleIndexOutbox row = row(8L, 80L, 19);

        service.markSendFailed(row, "broker down", 20);

        LambdaUpdateWrapper<ArticleIndexOutbox> update = captureUpdate();
        assertThat(update.getParamNameValuePairs().values())
                .contains(ArticleIndexOutbox.STATUS_FAILED, 20);
    }

    @Test
    void markSentOnlyAcceptsSendingRows() {
        when(mapper.update(isNull(), any())).thenReturn(0);

        boolean sent = service.markSent(9L);

        assertThat(sent).isFalse();
    }

    @Test
    void backoffGrowsExponentiallyAndCapsAtFiveMinutes() {
        assertThat(ArticleIndexOutboxService.backoffSeconds(1)).isEqualTo(5);
        assertThat(ArticleIndexOutboxService.backoffSeconds(2)).isEqualTo(10);
        assertThat(ArticleIndexOutboxService.backoffSeconds(3)).isEqualTo(20);
        assertThat(ArticleIndexOutboxService.backoffSeconds(7)).isEqualTo(300);
        assertThat(ArticleIndexOutboxService.backoffSeconds(20)).isEqualTo(300);
    }

    @SuppressWarnings("unchecked")
    private LambdaUpdateWrapper<ArticleIndexOutbox> captureUpdate() {
        ArgumentCaptor<LambdaUpdateWrapper<ArticleIndexOutbox>> captor =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(mapper).update(isNull(), captor.capture());
        return captor.getValue();
    }

    private static ArticleIndexOutbox row(Long id, Long articleId, int attempts) {
        ArticleIndexOutbox row = new ArticleIndexOutbox();
        row.setId(id);
        row.setArticleId(articleId);
        row.setStatus(ArticleIndexOutbox.STATUS_SENDING);
        row.setAttempts(attempts);
        row.setNextRetryAt(LocalDateTime.now());
        return row;
    }
}
