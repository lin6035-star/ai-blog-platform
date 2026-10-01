package com.hailin.blogsystem.entity.dto;

import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class AiIntent {
    private String intent;  //大类，比如 general_chat / user_profile_insight / article_action
    private Double confidence;  //LLM 对当前意图判断的置信度；分类失败时由后端置为 0
    private String actionType;  //细分动作，比如 likeArticle / followAuthor
    private List<String> requestedActions; // 文章复合动作候选；后端仍需白名单、冲突和完整性校验
    // 复合目标（动作 + 分析）里"分析"那一侧的诉求，必须是用户原话中连续出现的片段。
    // 语义同 topicEvidence：分类器只负责摘录，Planner 会验证它真实出现在原始 message 中，
    // 验证不通过一律不放行——不让 LLM 改写的摘要成为第二目标的权威文本。
    private String analysisGoalEvidence;

    /**
     * 候选任务目标（第三刀）：模型拆出的"用户想完成哪几件事"。
     *
     * <p>**它是候选不是权威**——每项含 type / params / goalEvidence / after / requires / onFailure，
     * 由 {@code TaskPlanner} 逐项校验（类型白名单 / 证据是否真在原文 / 冲突）后才变成正式 TaskPlan。
     * 因此这里的 type 允许是后端不认识的值，那属于正常输入，会被记为"未覆盖目标"而不是解析失败。
     *
     * <p>放 `AiIntent` 是因为它**属于分类器的建议**（与 `requestedActions` 同性质）；
     * 正式 `TaskPlan` 是后端产物，不放这里。
     */
    private List<Map<String, Object>> taskGoals;

    /** 复合请求里"这不是任务"（纯聊天）时为 CHAT——让"不是任务"成为显式结论而不是靠空数组隐式表达 */
    private String requestKind;
    private String articleId;  //文章ID
    private String authorId;
    private String userId;
    private String content;  //评论内容,补充文本
    private String target;
    private String param;

    private String topic;  //写作主题
    private String topicEvidence; // 用户原话中连续出现的主题片段
    private String categoryName;  //文章指定类型，默认为随笔
    private String requirements;  //用户补充要求

    private String keyWord;

    private String learningPlanRef;  //学习计划名称或关键词，只能来自用户原话
    private String learningStageRef;  //学习阶段或任务名称，只能来自用户原话
    private Integer learningPlanIndex;  //LLM 从后端注入的「该用户 ACTIVE 计划列表」中选中的序号（1 起）；未提及/选不出 → null
    private String learningPlanId;  //权威计划 ID——由后端把 learningPlanIndex 映射得到（越界即丢弃），不从 LLM 输出读取

    private String suggestedAction;  // CHAT / TOOL / WORKFLOW / CTA，LLM 建议，不是最终裁决
    private String suggestedWorkflowType;// LEARNING_PLAN / LEARNING_PROGRESS / LEARNING_ASSIST
    private String risk;// LOW / MEDIUM / HIGH
    private String reason; // 一句话解释

    private Boolean needsThinking;  // V3 通用思考模式：仅 GENERAL_CHAT 有意义，是否依赖记忆/前文/站内上下文需先查再答；默认 false
    private String needsThinkingReason;  // 判定依据一句话，仅抽样排查用，不进 UI、不影响路由
}
