package com.hailin.blogsystem.ai.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hailin.blogsystem.ai.AiJudgeModelSupport;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

/**
 * 文章域 Agent 决策器（V2.5 / V3.4）。
 *
 * 复用 LlmAgentStepDecider 的调用 / 解析 / 修复骨架，仅替换领域 prompt：
 * - 白名单：QUERY_ARTICLE / QUERY_MEMORY / SEARCH_RAG / ASK_USER / FINAL_ANSWER / SUGGEST_WORKFLOW / SUGGEST_WRITE
 * - 建议只能 OPTIMIZE_ARTICLE；V3.4 起 SUGGEST_WRITE 只支持内层 UPDATE_ARTICLE_TITLE（改自己文章标题）
 * - 必须先 QUERY_ARTICLE 验归属（观察含"文章不属于你 / 不存在"等失败 → FINAL_ANSWER 说明，不猜）
 *
 * <p>P2.1（2026-09-30）起 prompt 文本搬到 {@link ArticleDeciderPrompts}——按运行模式可裁剪。
 * 本类声明自己是 {@link ArticleDeciderPrompts.Mode#FULL}：语义与改造前完全一致，
 * 拼接结果由 {@code ArticleDeciderPromptTests} 锁定为**逐字相同**。
 */
@Component
public class ArticleAgentStepDecider extends LlmAgentStepDecider {

    public ArticleAgentStepDecider(ChatClient.Builder chatClientBuilder, ObjectMapper objectMapper,
                                   AiJudgeModelSupport aiJudgeModelSupport) {
        super(chatClientBuilder, objectMapper, aiJudgeModelSupport);
    }

    @Override
    protected String buildSystemPrompt(boolean repair) {
        return ArticleDeciderPrompts.systemPrompt(ArticleDeciderPrompts.Mode.FULL, repair);
    }

    /**
     * V3.13 Plan Preview：计划规则只在**首步**给，且 repair 轮只给一条短规则（保字段不被结构性丢失，
     * 又不用长规则挤占修复轮）。
     *
     * 领域规则不写基类——学习域 / 通用域不启用本能力，基类只负责追加本钩子返回值。
     */
    @Override
    protected String planPromptSection(int stepNo, boolean repair) {
        if (stepNo != 1) {
            return "";
        }
        if (repair) {
            return "\n如这是首次决策且用户请求包含多个子目标，请保留合法的顶层 \"plan\" 字符串数组；否则省略。\n";
        }
        return """

                JSON 顶层可选字段 "plan"——给用户看的执行计划：
                - 仅当用户请求需要**多个可观察子目标**才能完成时输出，2-4 条，每条是用户能读懂的目标
                  （如「先看整体结构」「再检查缓存击穿那一节」「结合你的写作偏好给建议」）
                - 禁止出现动作英文名与机制词：QUERY_xxx / SEARCH_RAG / JSON / prompt / 白名单 / 决策器 / 第 N 步
                - 简单的单一问题（如「整篇文章写得怎么样」）不要输出 plan
                - 想不出自然的说法就省略该字段（缺失不影响任何流程）
                """;
    }

    @Override
    protected String buildSummarizeSystemPrompt() {
        return """
                你是文章优化助手 Agent。根据提供的观察信息，用中文给用户一份简洁的文章分析结论。
                要求：
                - 3-6 句话，直接给结论，不要寒暄
                - 基于观察中的真实信息（标题、状态、字数、小标题结构、正文预览、记忆、文章知识）
                - 观察中没有的信息不要编造
                - 只输出结论正文，不要输出 JSON 或任何多余说明
                """;
    }
}
