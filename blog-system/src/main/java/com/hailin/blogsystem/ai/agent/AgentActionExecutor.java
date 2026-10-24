package com.hailin.blogsystem.ai.agent;

import com.hailin.blogsystem.entity.dto.PageContextDTO;

/**
 * Agent 只读动作执行器（V2.5 抽取公共接口）。
 *
 * <p>P2.2（2026-09-30）起返回 {@link AgentActionResult}：{@code summary} 仍是原来的 observation 文本
 * （历史 outputJson、开发者面板、观察流都不变），{@code payload} 是新增的结构化事实。
 * 第一版只有文章域填充 payload，其余域返回空 map——对它们是零行为变更。
 *
 * <p>白名单见 AgentStepActionType。
 *
 * pageContext：后端权威页面上下文（V2.5 起传入），
 * 领域执行器可据此定位当前文章等线索，但最终仍以数据库校验为准。
 *
 * 执行失败时抛异常，由 AbstractAgentRuntime 记录失败 step 并继续。
 */
public interface AgentActionExecutor {

    AgentActionResult execute(AgentStepDecision decision, Long userId, PageContextDTO pageContext);
}
