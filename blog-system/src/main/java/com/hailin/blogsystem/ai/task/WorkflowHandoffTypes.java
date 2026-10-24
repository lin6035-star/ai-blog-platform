package com.hailin.blogsystem.ai.task;

import java.util.Locale;
import java.util.Set;

/**
 * 5b 第一版开放的 Workflow 白名单（2026-10-01）。
 *
 * <p><b>为什么要有独立白名单，而不是直接问"枚举里有没有"</b>：
 * {@code AiWorkflowType} 是**领域登记表**（系统一共能创建哪些 Workflow），
 * 这里回答的是**编排层当下敢接哪些**——两者不是一回事。
 * 第一版只接 {@code OPTIMIZE_ARTICLE}：它的定位对象是"当前文章"（有可信锚点），
 * 完成副作用是"跳编辑器填稿"，与"低风险动作 + 高风险确认"这条验收链刚好匹配。
 *
 * <p>字段与关联按通用名称设计，但**注册表只开一项**（设计稿 §3.2）：不能因为字段可扩展
 * 就顺手把学习域 / 钱包域放进来——那会让第一次"暂停 → 确认 → 续跑"的验证混进一堆无关的校验分支。
 * 想加第二个 Workflow，成本应该是**可预期的**：白名单加一项 + 补"有没有锚点"的校验分派 + 一条验收链。
 *
 * <p><b>三处共用同一份白名单</b>：规划期参数校验（{@code TaskStepParamValidator}）、
 * 执行期前置校验（{@link WorkflowHandoffPreflight}）、handoff 执行器。各写一份必然漂移，
 * 而"一处放行、另一处拦"是最难查的那种问题。
 */
public final class WorkflowHandoffTypes {

    public static final String OPTIMIZE_ARTICLE = "OPTIMIZE_ARTICLE";

    private static final Set<String> SUPPORTED = Set.of(OPTIMIZE_ARTICLE);

    private WorkflowHandoffTypes() {
    }

    /** 归一化后判断是否在 5b 白名单内（大小写不敏感、容忍两侧空白） */
    public static boolean isSupported(String workflowType) {
        return workflowType != null && SUPPORTED.contains(workflowType.trim().toUpperCase(Locale.ROOT));
    }

    /** 供日志 / 报错文案用（不含用户数据，可以安全出现在内部日志） */
    public static Set<String> supported() {
        return SUPPORTED;
    }
}
