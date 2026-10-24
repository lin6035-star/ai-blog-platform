package com.hailin.blogsystem.ai;

import java.util.ArrayList;
import java.util.List;

/**
 * 分类器的**调用账本**（P3.0 测量口径，2026-10-01）。
 *
 * <p><b>为什么不能靠 {@code usage.getCallCount() > 1} 猜 repair</b>：
 * 分类器一次请求里，第二次调用可能是
 * <ul>
 *   <li>{@code REPAIR}——首轮 JSON 没解析出来，重试一次；</li>
 *   <li>{@code PLAN_LOCATOR}——命中「调整/攻坚已有计划」且点名了计划，带真实计划列表再分一次；</li>
 *   <li>将来候选 B 的 {@code EXTRACTOR} / {@code REPAIR_EXTRACTOR}。</li>
 * </ul>
 * 三者对 P3 的含义完全不同：repair 是**质量缺陷**，计划定位是**必要成本**，
 * Extractor 是**方案选择的结果**。用次数猜会把它们混成一个数。
 *
 * <p>另外，{@link #getHttpCallCount()} 统计的是"发出的调用"，**不等于**供应商看到的 HTTP 尝试次数
 * （Spring Retry 的 429 重试不在这里）——所以它**不能直接当 RPM 用**，限流压力要在重试层单独埋点。
 *
 * <p>本类只做记录，不改任何行为：所有方法在 {@code probe == null} 时由调用方跳过。
 */
public class ClassifierProbe {

    /** 一次调用在整条分类链里扮演的角色 */
    public enum CallRole {
        /** 首轮分类（绝大多数消息只走到这里） */
        FIRST_PASS,
        /** 首轮 JSON 解析失败后的重试 */
        REPAIR,
        /** 带真实计划列表的二次分类（只有点名计划的调整/攻坚才走） */
        PLAN_LOCATOR,
        /** 候选 B 第一段：路由分诊——只判"涉及哪些领域"，不做字段提取 */
        ROUTE,
        /** 候选 B 第二段：按路由结果装载本域规则后做提取 */
        EXTRACTOR
    }

    /** 这条消息最终的落点 */
    public enum Outcome {
        /** 正常分类出结果 */
        SUCCESS,
        /**
         * 计划定位那一次调用失败，但**首轮结果可用并已采用**——生产落点是成功的。
         *
         * <p>不区分它的话，报告会把这类样例算成"最终失败"，而实际上用户什么异常都感知不到
         * （分类器降级成 CALL_FAILED 是在 {@code callOnce} 里记的，但 {@code classify}
         * 只在定位成功时才覆盖首轮结果）。
         */
        SUCCESS_WITH_PLAN_LOCATOR_FALLBACK,
        /** 调用异常（429 / 超时 / 网络），或两次都没拿到可用 JSON */
        CALL_FAILED,
        /** 拿到了响应但两次都解析不出 JSON */
        PARSE_FAILED,
        /** 最终降级成普通聊天——**用户可见的结果**，原因看 {@link #getDetail()} */
        DEGRADED
    }

    private final List<CallRole> roles = new ArrayList<>();
    private Outcome outcome = Outcome.SUCCESS;
    private String detail;
    private int responseChars;

    public void recordRole(CallRole role) {
        if (role != null) {
            roles.add(role);
        }
    }

    public void markOutcome(Outcome outcome, String detail) {
        if (outcome != null) {
            this.outcome = outcome;
            this.detail = detail;
        }
    }

    /** 本次分类发出的调用序列（顺序即实际发生顺序） */
    public List<CallRole> getRoles() {
        return List.copyOf(roles);
    }

    public Outcome getOutcome() {
        return outcome;
    }

    public String getDetail() {
        return detail;
    }

    /** 记录一次响应的正文字符数（累计）——与推理 token 配合，才能判断优化的是 JSON 还是隐藏推理 */
    public void recordResponseChars(int chars) {
        if (chars > 0) {
            responseChars += chars;
        }
    }

    /** 本次分类所有响应正文的字符数之和 */
    public int getResponseChars() {
        return responseChars;
    }

    /** 发出的调用次数（**不含** Spring Retry 的 429 重试） */
    public int getHttpCallCount() {
        return roles.size();
    }

    public boolean repaired() {
        return roles.contains(CallRole.REPAIR);
    }

    public boolean locatedPlan() {
        return roles.contains(CallRole.PLAN_LOCATOR);
    }
}
