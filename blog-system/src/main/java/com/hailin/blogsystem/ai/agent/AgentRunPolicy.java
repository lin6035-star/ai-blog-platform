package com.hailin.blogsystem.ai.agent;

import com.hailin.blogsystem.entity.dto.AgentStepActionType;

import java.util.Set;

/**
 * 一次 Agent Run 的运行策略（2026-09-28，第二刀「动作 + 分析」）。
 *
 * <p>把「用哪个决策器 / 允许哪些动作 / 能否进入等待态」三件事从域实现里提出来，
 * 让同一个 Runtime 能按入口切换模式。analysis-only（复合请求里的分析段）就是这样来的：
 * 换一个 prompt 更窄的 decider + 更小的白名单 + 不允许 WAITING_USER。
 *
 * <p><b>为什么策略里必须带 decider</b>：{@code AgentStepDecider.decide(...)} 的签名里
 * 既没有 run 也没有模式，所以运行模式天然到不了 prompt 那一层。
 * 只换白名单挡不住 LLM 继续输出 ASK_USER——而白名单命中非法动作时骨架是
 * {@code markFailed} 直接终局的，不是退回循环，整轮就废了。
 *
 * <p><b>为什么不把模式挂在 run 实体上</b>：同理，decider 看不到 run；而且会把
 * 持久化状态和运行配置混在一起，将来若做跨请求续跑，瞬态标记必然丢失。
 */
public record AgentRunPolicy(
        AgentStepDecider decider,
        Set<AgentStepActionType> allowedActions,
        boolean allowWaitingUser
) {
}
