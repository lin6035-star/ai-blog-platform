package com.hailin.blogsystem.entity.vo;

import lombok.Data;

/**
 * Agent Run 详情·开发者原始视图（V4 第一刀，只读）。
 *
 * 组合而非继承：{@code run} 是与用户侧同源的**安全摘要**，{@code contextJson} 是**未清洗的原始
 * observations**（含文章正文片段等模型可见材料）。两者字段分开摆放，避免使用者忘记哪一份可信。
 */
@Data
public class AgentRunDevDetailVO {
    /** 与 {@code GET /api/ai/agent-runs/{id}} 同源的安全摘要。 */
    private AgentRunDetailVO run;
    /** 原始 contextJson（observations 序列化；可能有长度截断，展示时需标注来源）。 */
    private String contextJson;

    /*
     * ── 2026-09-30 Token 工程 P0-b：run 级 Token（**只走开发者通道**）──
     *
     * 为什么放外层而不是塞进 run（AgentRunDetailVO）：那个 VO 是**用户侧**接口的返回体，
     * 加 Token 字段等于把内部成本暴露给普通用户，与本模块「不向普通用户侧暴露内部 Token」
     * 的边界冲突。项目已有一致口径：AiMessageVO 同样不含 token 字段。
     *
     * 语义：**本 run 直接发起的 LLM 调用**（决策 decide/repair + Verifier + 到顶 summarize）。
     * P1 接入父子 run 后，这里再补 localTokens / subtreeTokens 两个查询派生值
     * （口径见 Token 设计稿 §5.4）；P0 没有子 run，totalTokens 即子树总量。
     */
    private Integer inputTokens;
    private Integer outputTokens;
    private Integer totalTokens;
}
