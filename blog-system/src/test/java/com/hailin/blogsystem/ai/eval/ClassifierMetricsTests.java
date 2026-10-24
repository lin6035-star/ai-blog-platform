package com.hailin.blogsystem.ai.eval;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P3.0 测量统计的离线单测。
 *
 * <p>统计逻辑是 A/B 对比的**度量尺**——尺子本身错了，后面跑多少在线样例都是白花钱。
 * 所以这一层必须离线可验证、不依赖 LLM。
 */
class ClassifierMetricsTests {

    @Test
    @DisplayName("百分位：空 / 单值 / 边界 p 都不越界")
    void percentileHandlesEdges() {
        assertThat(ClassifierMetrics.percentile(List.of(), 0.95)).isZero();
        assertThat(ClassifierMetrics.percentile(List.of(120L), 0.50)).isEqualTo(120);

        List<Long> values = LongStream.rangeClosed(1, 100).boxed().toList();
        assertThat(ClassifierMetrics.percentile(values, 0.50))
                .as("100 个值的 P50 是第 50 个")
                .isEqualTo(50);
        assertThat(ClassifierMetrics.percentile(values, 0.95)).isEqualTo(95);
        assertThat(ClassifierMetrics.percentile(values, 1.0))
                .as("p=1 取最大值，不能下标越界")
                .isEqualTo(100);
    }

    @Test
    @DisplayName("百分位：与输入顺序无关（必须自己排序）")
    void percentileIsOrderIndependent() {
        List<Long> shuffled = List.of(900L, 100L, 500L, 300L, 700L);

        assertThat(ClassifierMetrics.percentile(shuffled, 1.0)).isEqualTo(900);
        assertThat(ClassifierMetrics.percentile(shuffled, 0.2)).isEqualTo(100);
    }

    @Test
    @DisplayName("报告：全局口径 + 隐藏推理 + 分层 + repair 都要出现")
    void reportContainsGlobalAndPerLayerNumbers() {
        List<ClassifierMetrics.Sample> samples = List.of(
                sample("聊天样例", "chat", 1, 800, 60, 860, 20, 0, 210, 900, "SUCCESS", "FIRST_PASS", "PASS"),
                sample("复合样例", "workflow", 2, 1600, 200, 1800, 120, 512, 640, 2100,
                        "SUCCESS", "FIRST_PASS+REPAIR", "PASS"));

        String report = ClassifierMetrics.report(samples, "基线");

        assertThat(report).contains("基线");
        assertThat(report).contains("样本数: 2");
        assertThat(report).contains("总 Token: 2660");
        assertThat(report).contains("总调用次数: 3");
        assertThat(report).contains("平均调用次数: 1.5");
        assertThat(report).contains("P95 延迟(ms): 2100");
        assertThat(report).contains("repair 次数: 1 / 2");
        assertThat(report)
                .as("隐藏推理必须单独出现——否则会把'模型在思考'误读成'JSON 太长'")
                .contains("其中隐藏推理 140");
        // 分层段：A/B 的决策依据在这里，不在总体平均
        assertThat(report).contains("chat");
        assertThat(report).contains("workflow");
    }

    @Test
    @DisplayName("异常落点必须单独统计——降级不能被当成正常样例计入性能排名")
    void abnormalOutcomeIsCountedSeparately() {
        List<ClassifierMetrics.Sample> samples = List.of(
                sample("正常", "chat", 1, 800, 60, 860, 0, 0, 200, 900, "SUCCESS", "FIRST_PASS", "PASS"),
                sample("降级", "chat", 1, 800, 60, 860, 0, 0, 0, 400, "DEGRADED", "FIRST_PASS", "NONE"));

        String report = ClassifierMetrics.report(samples, "异常落点检查");

        assertThat(report).contains("异常落点: 1 / 2");
        assertThat(report).contains("DEGRADED");
    }

    @Test
    @DisplayName("硬门失败的样例要被点名，并提示不得参与性能结论")
    void hardGateFailureIsSurfaced() {
        List<ClassifierMetrics.Sample> samples = List.of(
                sample("过了", "chat", 1, 800, 60, 860, 0, 0, 200, 900, "SUCCESS", "FIRST_PASS", "PASS"),
                sample("漏目标", "composite", 1, 800, 60, 860, 0, 0, 200, 500, "SUCCESS", "FIRST_PASS", "FAIL"));

        String report = ClassifierMetrics.report(samples, "硬门检查");

        assertThat(report).contains("硬门: 通过 1 / 失败 1 / 未绑定 0");
        assertThat(report)
                .as("硬门失败的样例不能悄悄混进 Token 排名")
                .contains("不得");
        assertThat(report).contains("硬门失败=1");
    }

    @Test
    @DisplayName("报告必须写明 P95 的样本内局限（n 小时它就是最大值）")
    void reportStatesP95Caveat() {
        String report = ClassifierMetrics.report(
                List.of(sample("唯一", "chat", 1, 1, 1, 2, 0, 0, 1, 10, "SUCCESS", "FIRST_PASS", "NONE")),
                "局限说明");

        assertThat(report).contains("不是线上 P95 估计");
    }

    @Test
    @DisplayName("报告：超长样例名不撑破列宽")
    void reportTruncatesLongName() {
        String longName = "样例".repeat(60);
        String report = ClassifierMetrics.report(
                List.of(sample(longName, "chat", 1, 1, 1, 2, 0, 0, 1, 10, "SUCCESS", "FIRST_PASS", "NONE")),
                "截断检查");

        assertThat(report).doesNotContain(longName);
        assertThat(report).contains("…");
    }

    private static ClassifierMetrics.Sample sample(
            String name, String layer, int calls, int prompt, int completion, int total,
            int reasoning, int cached, int chars, long ms, String outcome, String roles, String gate) {
        return new ClassifierMetrics.Sample(name, layer, calls, prompt, completion, total,
                reasoning, cached, chars, ms, outcome, roles, gate, null);
    }
}
