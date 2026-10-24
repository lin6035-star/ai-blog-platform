package com.hailin.blogsystem.ai;

/**
 * 日志值安全化（C0.2，2026-10-01）。
 *
 * <p>模型给的原始值（动作名、导航目标、Workflow 类型……）可能超长、带换行、甚至塞控制字符。
 * 它们**只能进内部日志，不能进用户回复**；而进日志之前也要单行化 + 限长——
 * 否则一条日志会被撑成几十行，排查时反而看不清。
 *
 * <p><b>为什么抽成公共工具</b>：这条规则要在任务执行器与三个 Agent Runtime 里共用
 * （C0.2 一次性覆盖 6 处）。各写一份迟早漂移，而漂移掉的那一份通常就是**忘了限长**的那份。
 */
public final class LogValues {

    private static final int DEFAULT_MAX = 40;

    private LogValues() {
    }

    /** 单行化 + 默认限长（40 字符）；null 安全 */
    public static String safe(String raw) {
        return safe(raw, DEFAULT_MAX);
    }

    /** 单行化 + 指定限长；{@code max <= 0} 表示不限长 */
    public static String safe(String raw, int max) {
        if (raw == null) {
            return "";
        }
        String oneLine = raw.replaceAll("\\s+", " ").trim();
        if (max <= 0 || oneLine.length() <= max) {
            return oneLine;
        }
        return oneLine.substring(0, max) + "…";
    }
}
