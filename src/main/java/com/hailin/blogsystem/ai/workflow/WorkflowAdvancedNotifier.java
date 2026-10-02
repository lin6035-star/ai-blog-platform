package com.hailin.blogsystem.ai.workflow;

import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * "Workflow 状态已推进"的发布口（5b）。
 *
 * <p>为什么用 Spring 事件而不是直接调恢复服务：恢复必须在**事务提交之后**做——
 * Workflow 状态还没提交就去续跑，读到的还是旧状态，会得出"还没终态、不用恢复"的错误结论。
 * 监听端用 {@code @TransactionalEventListener(AFTER_COMMIT)} 保证这一点。
 *
 * <p>为什么不让发布方自己判断"是不是终态"：判断需要读库，而发布方此刻在事务里；
 * 把判断留给监听端（它拿着的是提交后的状态）更省事也更准。
 */
@Component
@RequiredArgsConstructor
public class WorkflowAdvancedNotifier {

    private final ApplicationEventPublisher publisher;

    public void notifyAdvanced(Long workflowRunId) {
        if (workflowRunId == null) {
            return;
        }
        publisher.publishEvent(new WorkflowAdvancedEvent(workflowRunId));
    }
}
