package com.hailin.blogsystem.ai.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hailin.blogsystem.ai.AiJudgeModelSupport;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

/**
 * 文章域「只分析」决策器（2026-09-28，第二刀：动作 + 分析）。
 *
 * <p>给复合请求的分析段用：沿用同一套文章定位 / 锚点 / focus 逻辑，
 * 但**不声明任何会产生等待态的终态动作**——本模式的验收边界是单请求闭环（设计稿 §4.7）。
 *
 * <p><b>P2.1（2026-09-30）改成按模式组装，不再是「完整 prompt + 末尾覆盖声明」</b>：
 * 旧做法每一步都发 {@code ASK_USER / SUGGEST_WORKFLOW / SUGGEST_WRITE} 的动作说明与分界规则，
 * 再在末尾声明禁止——实测 analysis-only 正常 prompt 3986 字符，其中禁用动作的声明与规则占 1200+ 字符。
 * 当时的理由是「终态动作说明散落在十几处、抽不出独立片段」；P2.1 的做法是**把这些散落的说明
 * 按语义单元拆成片段**（{@link ArticleDeciderPrompts}），由模式决定装载哪些，
 * 连交织在「指代判定 / anchorMode」里的 ASK_USER 分支也按模式改写，而不是整段保留。
 *
 * <p><b>它只是三层约束的第一层</b>：白名单与 Verifier 转问路径由 {@link AgentRunPolicy} 一并收窄。
 * 只改 prompt 挡不住模型；只改白名单更糟——骨架遇到非法动作是直接 {@code markFailed} 终局，
 * 不是退回循环，整轮会废。
 */
@Component
public class ArticleAnalysisStepDecider extends ArticleAgentStepDecider {

    public ArticleAnalysisStepDecider(ChatClient.Builder chatClientBuilder, ObjectMapper objectMapper,
                                      AiJudgeModelSupport aiJudgeModelSupport) {
        super(chatClientBuilder, objectMapper, aiJudgeModelSupport);
    }

    @Override
    protected String buildSystemPrompt(boolean repair) {
        return ArticleDeciderPrompts.systemPrompt(ArticleDeciderPrompts.Mode.ANALYSIS_ONLY, repair);
    }
}
