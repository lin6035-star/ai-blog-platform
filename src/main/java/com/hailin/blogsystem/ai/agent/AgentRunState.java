package com.hailin.blogsystem.ai.agent;

/**
 * Agent run 的**领域结构化状态**（P2.2，Token 设计稿 §7.2 / §7.6）。
 *
 * <p><b>为什么需要它</b>：{@code List<String> observations} 目前同时充当决策上下文、Verifier 证据、
 * 调试面板材料与 maxSteps 总结材料（§7.1）——每一步都要把整条 observation 流重新裁剪一次塞给模型，
 * 而其中很大一部分是**已完成的动作说明**与**已被新证据替换的旧结果**。
 * 让领域把执行结果归约成结构化事实，决策上下文就能按语义槽位组装（§7.4 的槽位预算），
 * 而不是重放整条文本流。
 *
 * <p><b>per-run 生命周期（硬约束）</b>：实现必须是**每次 run 新建的局部对象**——
 * 由 {@link AbstractAgentRuntime#newRunState} 工厂产出。
 * 绝不能挂在单例 Runtime 的字段上：那会让并发请求互相污染状态。
 *
 * <p><b>第一版只对文章域启用</b>：基类 {@code newRunState} 默认返回 {@code null}，
 * 学习 / 通用 / 钱包域继续走 observation 重放——对它们是零行为变更。
 *
 * <p><b>实现只消费 {@code payload}</b>：不解析中文 observation。
 * 命中与未命中都会返回一段文本，只有结构化字段能区分（这正是 §7.3 否掉「路 A」的原因）。
 */
public interface AgentRunState {

    /**
     * 反馈的清理条件。它是运行期状态键，不是给用户看的文案。
     */
    enum FeedbackClearKey {
        ARTICLE_CONTENT,
        MEMORY,
        RAG,
        QUERY_REQUIRED;

        static FeedbackClearKey from(Verdict.MissingEvidence missingEvidence) {
            if (missingEvidence == null) {
                return null;
            }
            return switch (missingEvidence) {
                case ARTICLE_CONTENT -> ARTICLE_CONTENT;
                case MEMORY -> MEMORY;
                case RAG -> RAG;
                case OTHER -> null;
            };
        }
    }

    /**
     * 归约一次动作结果。
     *
     * <p>成功与失败各调用一次（失败的 payload 带 {@code failed=true}），
     * 所以实现可以据此维护 {@code completedActions} / {@code latestError}。
     */
    void reduce(AgentStepDecision decision, AgentActionResult result);

    /**
     * 本步给 Decider 的上下文文本。
     *
     * <p>按 §7.6 只包含：目标 / 当前步数 / 已确认目标对象 / 证据槽位 /
     * 已完成动作 / 上次缺失证据 / 当前可用动作——不再发送已完成动作的完整说明与旧 observation。
     */
    String decisionContext();

    /** 给 Verifier 的事实证据；不能包含上一次 Verifier 反馈等评价性内容。 */
    default String verificationEvidence() {
        return decisionContext();
    }

    /**
     * 给 maxSteps 收尾总结用的运行上下文。
     *
     * <p>与 Verifier 不同，总结需要知道执行失败、后端拒绝等运行事实，才能如实解释为什么没有完成。
     */
    default String summaryContext() {
        return decisionContext();
    }

    /**
     * 运行期反馈（P2.3 修订）：拒绝原因、拦截提示等**不是动作执行结果**的信息。
     *
     * <p><b>为什么必须有它</b>：结构化路径下 Decider 只读 {@link #decisionContext()}，
     * 而 Verifier 的 NEED_MORE、重复查询拦截、Workflow / 写提案拒绝这些提示原先**只写进 observations**——
     * 模型下一轮看不到"刚才为什么被拒、接下来该补什么"，会重复同样的动作直到步数耗尽。
     *
     * <p>两边都写：observations 保审计完整，结构化状态保模型准确。
     */
    default void noteFeedback(String message) {
        noteFeedback(message, null);
    }

    /**
     * @param clearKey 反馈的结构化清理条件；对应条件满足时清除。{@code null} = 保留到本 run 结束。
     *
     * <p><b>为什么要类型而不是只比字符串</b>：反馈的文案以「系统提示」开头，而缺失项以「文章内容」开头，
     * 靠前缀清不掉——Verifier 说"缺正文细节"、随后带 focus 的补查成功了，旧反馈却还留着，
     * 下一轮 Verifier 又看到"证据不足"，形成自我强化。
     */
    default void noteFeedback(String message, FeedbackClearKey clearKey) {
    }

    /**
     * 上一步是否产出了**新**证据（P2.3 修订）。
     *
     * <p>Verifier 的 R3 规则「本轮观察与前序重复 → 放行，避免空转到顶」原先靠比对**多条** observation；
     * 结构化路径只喂一份槽位证据，那个判据必然失效（`size() < 2` 直接返回 false），
     * 规则会**静默失效**——本该放行的继续被拦。结构化状态本来就有去重键，判断比比对文本更准。
     *
     * <p>默认 {@code true}：未知即当作有新证据（保守，保持原有宽松语义）。
     */
    default boolean lastStepHadNewEvidence() {
        return true;
    }
}
