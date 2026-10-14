package com.hailin.blogsystem;

import com.hailin.blogsystem.ai.TokenUsageAccumulator;
import com.hailin.blogsystem.entity.LearningPlans;
import com.hailin.blogsystem.entity.dto.AiIntent;
import com.hailin.blogsystem.entity.dto.PageContextDTO;
import com.hailin.blogsystem.service.impl.AiMessageServiceImpl;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Workflow 入口 token 契约。
 *
 * <p>这些入口是 private，普通行为测试需要搭起整个消息服务和异步准入链路；
 * 这里用反射锁住更关键的结构性边界：分类器 usage 必须能从主路由一路传到五个
 * Workflow helper 和统一异常出口。否则 Workflow 自己的 step token 还在，消息级
 * routing token 会静默丢失。</p>
 */
class AiMessageServiceWorkflowTokenContractTests {

    @Test
    void workflowEntrypointsAndErrorExitAcceptRouteUsage() throws Exception {
        assertThat(hasMethod(
                "streamCreateArticleWorkflowFromIntent",
                String.class, AiIntent.class, PageContextDTO.class, String.class,
                Long.class, Long.class, TokenUsageAccumulator.class
        )).isTrue();
        assertThat(hasMethod(
                "streamArticleOptimizeWorkflowFromIntent",
                String.class, AiIntent.class, PageContextDTO.class, String.class,
                Long.class, Long.class, TokenUsageAccumulator.class
        )).isTrue();
        assertThat(hasMethod(
                "streamLearningPlanWorkflowFromIntent",
                String.class, AiIntent.class, PageContextDTO.class, String.class,
                Long.class, Long.class, String.class, TokenUsageAccumulator.class
        )).isTrue();
        assertThat(hasMethod(
                "streamLearningProgressWorkflowFromIntent",
                String.class, LearningPlans.class, List.class, PageContextDTO.class, String.class,
                Long.class, Long.class, String.class, TokenUsageAccumulator.class
        )).isTrue();
        assertThat(hasMethod(
                "streamLearningAssistWorkflowFromIntent",
                String.class, LearningPlans.class, List.class, PageContextDTO.class, String.class,
                Long.class, Long.class, String.class, TokenUsageAccumulator.class
        )).isTrue();
        assertThat(hasMethod(
                "emitRejectedOrError",
                FluxSink.class, Long.class, Long.class, String.class, String.class,
                Throwable.class, TokenUsageAccumulator.class
        )).isTrue();
    }

    private boolean hasMethod(String name, Class<?>... parameterTypes) {
        try {
            Method method = AiMessageServiceImpl.class.getDeclaredMethod(name, parameterTypes);
            return method != null;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }
}
