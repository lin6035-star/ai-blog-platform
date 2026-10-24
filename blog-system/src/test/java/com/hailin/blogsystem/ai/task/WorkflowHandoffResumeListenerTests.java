package com.hailin.blogsystem.ai.task;

import com.hailin.blogsystem.ai.workflow.WorkflowAdvancedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * AFTER_COMMIT 触发器的**边界**（5b 阶段 3）。
 *
 * <p>重点不是"能转发"，而是"转发失败时不许把成功的操作表现成失败"：
 * 事件发生在 Workflow 事务提交之后，那时用户的操作已经成功了。
 */
class WorkflowHandoffResumeListenerTests {

    private TaskWorkflowContinuationService continuationService;
    private WorkflowHandoffResumeListener listener;

    @BeforeEach
    void setUp() {
        continuationService = mock(TaskWorkflowContinuationService.class);
        listener = new WorkflowHandoffResumeListener(continuationService);
    }

    @Test
    @DisplayName("正常事件：转交给幂等恢复入口")
    void delegatesToContinuation() {
        listener.onAdvanced(new WorkflowAdvancedEvent(99L));

        verify(continuationService).onWorkflowAdvanced(99L);
    }

    @Test
    @DisplayName("恢复抛异常必须被吞掉——Workflow 已经提交成功，不能反过来表现成失败")
    void swallowsContinuationFailure() {
        doThrow(new IllegalStateException("恢复炸了")).when(continuationService).onWorkflowAdvanced(99L);

        assertThatCode(() -> listener.onAdvanced(new WorkflowAdvancedEvent(99L)))
                .as("异常必须留在日志里；抛出去会让一次已经成功的 Workflow 操作变成 HTTP 失败")
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("空事件 / 空 id：什么都不做，也不抛")
    void ignoresEmptyEvent() {
        listener.onAdvanced(null);
        listener.onAdvanced(new WorkflowAdvancedEvent(null));

        verifyNoInteractions(continuationService);
    }
}
