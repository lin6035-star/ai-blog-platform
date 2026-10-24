package com.hailin.blogsystem.ai.eval;

import java.util.List;

/**
 * P3.0 分类器测量统计（测试侧工具）。
 *
 * <p><b>为什么要单独抽出来</b>：P3 要比较的两个候选，差别在**调用次数**、**延迟分布**与
 * **是否降级**上，而不是单看 token 总量。统计口径必须是同一个实现——否则 A 用一套算法、
 * B 用另一套，比出来的差异其实是统计方式的差异。
 *
 * <p>为什么不复用 {@code AgentTokenBaselineTests} 里那套：那个类是 Agent run 的基线
 * （多步 + 子树口径），与分类器"一次请求一次分类"的口径不同，混用会互相污染。
 */
public final class ClassifierMetrics {

    private ClassifierMetrics() {
    }

    /**
     * 一次分类的测量样本。
     *
     * @param calls          发出的调用次数（**不含** Spring Retry 的 429 重试，所以不能当 RPM）
     * @param reasoningTokens 隐藏推理 token——它是 {@code completionTokens} 的**子集**。
     *                        不分出来就会把"模型在思考"误读成"JSON 太长"，去优化一个不大的东西
     * @param cachedTokens   命中 prompt cache 的输入量（不计费、更快；不拆出来 A/B 的省钱结论会失真）
     * @param responseChars   模型返回的正文字符数——与 reasoningTokens 配合，才能判断
     *                        "输出优化"到底在缩 JSON、还是在缩看不见的推理
     * @param outcome        SUCCESS / DEGRADED / PARSE_FAILED / CALL_FAILED
     * @param roles          调用角色序列，如 {@code FIRST_PASS} / {@code FIRST_PASS+REPAIR}
     *                       —— 不能靠次数猜：第二次调用可能是 repair，也可能是计划定位
     */
    public record Sample(
            String name,
            String layer,
            int calls,
            int promptTokens,
            int completionTokens,
            int totalTokens,
            int reasoningTokens,
            int cachedTokens,
            int responseChars,
            long elapsedMs,
            String outcome,
            String roles,
            /**
             * 硬门结果：{@code PASS} / {@code FAIL} / {@code NONE}（未绑定评测集）。
             * <b>FAIL 的样例不进性能排名</b>——否则漏掉第二目标的方案可能凭低 Token 获胜。
             */
            String hardGate,
            /**
             * 硬门失败的**原因**。
             *
             * <p>为什么必须落进报告文件：控制台编码会把中文断言消息吃掉，而"哪条契约挂了"
             * 恰恰是 P3 最需要的信息——只看一个 FAIL 等于没说。
             */
            String gateDetail
    ) {

        /**
         * 只要不是"可用落点"就算异常。
         *
         * <p>{@code SUCCESS_WITH_PLAN_LOCATOR_FALLBACK} **不算异常**——那是首轮结果被采用、
         * 用户无感知的情况；把它算成失败会让报告失真。
         */
        public boolean abnormal() {
            return outcome != null
                    && !"SUCCESS".equals(outcome)
                    && !"SUCCESS_WITH_PLAN_LOCATOR_FALLBACK".equals(outcome);
        }

        public boolean repaired() {
            return roles != null && roles.contains("REPAIR");
        }
    }

    /**
     * 最近邻百分位（{@code p ∈ (0,1]}）。
     *
     * <p>样本量只有十几条时，插值法会造出"实际不存在"的延迟值（比如 P95 落在两点之间），
     * 而我们要的是"这一批里第 95 分位那条有多慢"——最近邻更诚实。
     *
     * <p><b>但它有个必须说明的局限</b>：{@code ceil(0.95 × 18) - 1 = 17}，也就是 n=18 时
     * P95 **就是这批数据的最大值**；分层后每层只有 2~3 条时，层内 P95 同样等于层内最大值。
     * 它适合发现异常慢样例，**不能当作线上 P95 的估计**。
     */
    public static long percentile(List<Long> values, double p) {
        if (values == null || values.isEmpty()) {
            return 0;
        }
        List<Long> sorted = values.stream().sorted().toList();
        int index = (int) Math.ceil(p * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
    }

    /** 汇总报告：逐例明细 + 全局口径 + **分层**统计 */
    public static String report(List<Sample> samples, String title) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== ").append(title).append(" ===\n");
        sb.append("样本数: ").append(samples.size()).append('\n').append('\n');

        sb.append(String.format("%-30s %4s %6s %6s %6s %6s %7s %6s %7s %-13s %-9s %s%n",
                "样本", "调用", "输入", "输出", "推理", "缓存", "总tok", "字符", "耗时ms",
                "结果", "硬门", "角色"));
        for (Sample s : samples) {
            sb.append(String.format("%-30s %4d %6d %6d %6d %6d %7d %6d %7d %-13s %-9s %s%n",
                    truncate(s.name(), 30), s.calls(), s.promptTokens(), s.completionTokens(),
                    s.reasoningTokens(), s.cachedTokens(), s.totalTokens(), s.responseChars(),
                    s.elapsedMs(), s.outcome(), s.hardGate(), s.roles()));
            if ("FAIL".equals(s.hardGate()) && s.gateDetail() != null) {
                sb.append("      ↳ 硬门失败：").append(truncate(oneLine(s.gateDetail()), 180)).append('\n');
            }
        }

        int promptSum = samples.stream().mapToInt(Sample::promptTokens).sum();
        int completionSum = samples.stream().mapToInt(Sample::completionTokens).sum();
        int totalSum = samples.stream().mapToInt(Sample::totalTokens).sum();
        int reasoningSum = samples.stream().mapToInt(Sample::reasoningTokens).sum();
        List<Long> latencies = samples.stream().map(Sample::elapsedMs).toList();

        sb.append("\n--- 全局 ---\n");
        sb.append("总 Token: ").append(totalSum).append('\n');
        sb.append("  输入 ").append(promptSum).append("（").append(share(promptSum, totalSum)).append("）")
                .append(" / 输出 ").append(completionSum).append("（").append(share(completionSum, totalSum)).append("）")
                .append('\n');
        sb.append("  其中隐藏推理 ").append(reasoningSum)
                .append("（占输出 ").append(share(reasoningSum, completionSum)).append("）")
                .append('\n');
        sb.append("总调用次数: ").append(samples.stream().mapToInt(Sample::calls).sum()).append('\n');
        sb.append("平均调用次数: ")
                .append(samples.stream().mapToInt(Sample::calls).average().orElse(0)).append('\n');
        sb.append("P50 延迟(ms): ").append(percentile(latencies, 0.50)).append('\n');
        sb.append("P95 延迟(ms): ").append(percentile(latencies, 0.95))
                .append("   ← n=").append(samples.size()).append(" 时即最大值，不是线上 P95 估计\n");
        sb.append("repair 次数: ").append(samples.stream().filter(Sample::repaired).count())
                .append(" / ").append(samples.size()).append('\n');
        sb.append("异常落点: ").append(samples.stream().filter(Sample::abnormal).count())
                .append(" / ").append(samples.size())
                .append("（降级 / 解析失败 / 调用失败都算——它们不能被当成正常样例计入性能排名）\n");
        long gateFailed = samples.stream().filter(s -> "FAIL".equals(s.hardGate())).count();
        sb.append("硬门: 通过 ").append(samples.stream().filter(s -> "PASS".equals(s.hardGate())).count())
                .append(" / 失败 ").append(gateFailed)
                .append(" / 未绑定 ").append(samples.stream().filter(s -> "NONE".equals(s.hardGate())).count())
                .append('\n');
        if (gateFailed > 0) {
            sb.append("  ⚠ 硬门失败的样例**不得**参与性能结论——先修正确性，再比 Token\n");
        }

        // 分层才是决策依据：P3 要知道"哪一层被省下来了、哪一层被拖慢了"，
        // 总体平均会把两者抵消成一个看不出问题的数字
        sb.append("\n--- 分层 ---\n");
        samples.stream().map(Sample::layer).distinct().forEach(layer -> {
            List<Sample> group = samples.stream().filter(s -> layer.equals(s.layer())).toList();
            sb.append(String.format("%-18s n=%-3d 总tok=%-7d 均调用=%.2f 推理=%-6d P95=%-7d repair=%d 异常=%d 硬门失败=%d%n",
                    layer, group.size(),
                    group.stream().mapToInt(Sample::totalTokens).sum(),
                    group.stream().mapToInt(Sample::calls).average().orElse(0),
                    group.stream().mapToInt(Sample::reasoningTokens).sum(),
                    percentile(group.stream().map(Sample::elapsedMs).toList(), 0.95),
                    group.stream().filter(Sample::repaired).count(),
                    group.stream().filter(Sample::abnormal).count(),
                    group.stream().filter(s -> "FAIL".equals(s.hardGate())).count()));
        });
        return sb.toString();
    }

    private static String share(int part, int whole) {
        if (whole <= 0) {
            return "n/a";
        }
        return String.format("%.2f%%", part * 100.0 / whole);
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max - 1) + "…";
    }

    /** 断言消息是多行的；压成一行才不至于把报告撑散 */
    private static String oneLine(String text) {
        return text == null ? "" : text.replaceAll("\\s+", " ").trim();
    }
}
