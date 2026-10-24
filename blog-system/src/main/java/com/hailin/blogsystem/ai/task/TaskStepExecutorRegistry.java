package com.hailin.blogsystem.ai.task;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 步骤执行器注册表（第三刀）——{@link StepType} → {@link TaskStepExecutor} 的单点登记。
 *
 * <p>只做查找，**不负责**意图判断、计划生成、失败策略——那些是 Orchestrator 的职责。
 * 有它，Orchestrator 才不必写一条会随能力增长而膨胀的大 switch。
 *
 * <p>与 {@code AgentRuntimeRouteRegistry} 同构：按类型解析 + 未登记时返回 null 由调用方处置。
 * 调用方（路由层）据此判断"计划里的 step 是否全部可执行"——这是并存期的路由判据（设计稿 §5.1）。
 */
@Component
@Slf4j
public class TaskStepExecutorRegistry {

    private final Map<StepType, TaskStepExecutor> byType = new EnumMap<>(StepType.class);

    public TaskStepExecutorRegistry(List<TaskStepExecutor> executors) {
        for (TaskStepExecutor executor : executors) {
            StepType type = executor.supports();
            if (type == null) {
                throw new IllegalStateException(
                        "执行器未声明 supports()：" + executor.getClass().getName());
            }
            TaskStepExecutor previous = byType.put(type, executor);
            if (previous != null) {
                // 同类型注册两个执行器 = 分派不确定，属于装配期错误，直接拒绝而不是静默覆盖
                throw new IllegalStateException(
                        "StepType " + type + " 被重复注册："
                                + previous.getClass().getName() + " / " + executor.getClass().getName());
            }
        }
        if (!byType.isEmpty()) {
            log.info("[TASK-EXEC] 已注册步骤执行器: {}", byType.keySet());
        }
    }

    /** 未登记返回 null——调用方据此判定该计划"不可整体执行"，退回旧路径 */
    public TaskStepExecutor resolve(StepType type) {
        return byType.get(type);
    }

    public boolean supports(StepType type) {
        return byType.containsKey(type);
    }
}
