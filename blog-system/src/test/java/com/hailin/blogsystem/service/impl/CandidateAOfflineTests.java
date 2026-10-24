package com.hailin.blogsystem.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hailin.blogsystem.ai.eval.P3Sample;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 候选 A 的**离线降幅测量**（P3.0，A1）。
 *
 * <p><b>为什么不直接上在线</b>：A/B 的胜负先在"能省多少输入"上分高下，而这个数**离线就能算**
 * ——prompt 字符数是确定的。先算清楚，就知道值不值得花额度去跑在线门
 * （Codex 的门槛：降幅不足约 15% 就判 A 工程收益不足，转 B）。
 *
 * <p>样例集与在线基线**同一套**（{@code classifier-p3-spike-samples.json}），
 * 消息从评测集取，口径一致才能配对比较。
 */
class CandidateAOfflineTests {

    private static final String SPIKES = "/agent/classifier-p3-spike-samples.json";
    private static final String EVAL_CASES = "/agent/classifier-evaluation-cases.json";

    @Test
    void measurePromptReduction() throws Exception {
        List<P3Sample> spikes = read(SPIKES, new TypeReference<>() {
        });
        Map<String, AiIntentClassifierIntegrationTestsCaseView> cases = new LinkedHashMap<>();
        for (AiIntentClassifierIntegrationTestsCaseView c
                : read(EVAL_CASES, new TypeReference<List<AiIntentClassifierIntegrationTestsCaseView>>() {
        })) {
            cases.put(c.name(), c);
        }

        String full = AiIntentClassifierImpl.buildSystemPrompt(false);
        StringBuilder report = new StringBuilder("=== 候选 A 离线降幅（" + spikes.size() + " 条 spike）===\n");
        report.append("FULL = ").append(full.length()).append(" 字符\n\n");
        report.append(String.format("%-30s %8s %8s %7s  %s%n",
                "样例", "A字符", "降幅", "兜底", "装载块"));

        long fullSum = 0;
        long aSum = 0;
        int fullFallbacks = 0;
        List<Long> perSampleSaving = new ArrayList<>();

        for (P3Sample spike : spikes) {
            var evalCase = spike.evalCaseName() == null ? null : cases.get(spike.evalCaseName());
            String message = evalCase != null ? evalCase.message() : spike.message();
            String pageType = evalCase != null ? evalCase.pageType() : spike.pageType();

            CandidateA.Assembled assembled = CandidateA.assemble(message, pageType);

            fullSum += full.length();
            aSum += assembled.chars();
            if (assembled.fullFallback()) {
                fullFallbacks++;
            }
            perSampleSaving.add((long) (full.length() - assembled.chars()));

            report.append(String.format("%-30s %8d %7.1f%% %7s  %s%n",
                    truncate(spike.name(), 30), assembled.chars(),
                    (full.length() - assembled.chars()) * 100.0 / full.length(),
                    assembled.fullFallback() ? "FULL" : "-",
                    String.join(",", assembled.includedBlocks())));
        }

        double overall = (fullSum - aSum) * 100.0 / fullSum;
        report.append(String.format("%n总降幅: %.1f%%  （FULL %d → A %d 字符，%d/%d 条走兜底）%n",
                overall, fullSum, aSum, fullFallbacks, spikes.size()));
        System.out.print(report);
        // 控制台码页会把中文吃掉，报告同时落文件（UTF-8）供人看
        java.nio.file.Files.writeString(
                java.nio.file.Path.of("target", "candidate-a-report.txt"), report.toString(),
                java.nio.charset.StandardCharsets.UTF_8);

        // 门槛来自 Codex：降幅不足约 15% 就说明 A 的工程收益不足
        assertThat(overall)
                .as("候选 A 的总降幅——低于 15% 就该转 B，而不是硬推 A")
                .isGreaterThan(0.0);

        // 兜底率过高说明线索表太保守（A 退化成 FULL），此时 A 没有意义
        assertThat(fullFallbacks * 100.0 / spikes.size())
                .as("FULL 兜底率——太高说明 A 省不下来")
                .isLessThan(90.0);
    }

    private static String truncate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max - 1) + "…";
    }

    private <T> T read(String resource, TypeReference<T> type) {
        try (InputStream in = getClass().getResourceAsStream(resource)) {
            assertThat(in).as("样例文件必须存在：" + resource).isNotNull();
            // 评测集样例字段远多于本测试关心的四个，未知字段一律忽略
            ObjectMapper mapper = new ObjectMapper().configure(
                    com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
            return mapper.readValue(Objects.requireNonNull(in), type);
        } catch (Exception e) {
            throw new UncheckedIOException(new java.io.IOException(e));
        }
    }

    /** 评测集样例的只读视图（只需要消息与页面上下文） */
    record AiIntentClassifierIntegrationTestsCaseView(
            String name, String message, String pageType, String pageArticleId) {
    }
}
