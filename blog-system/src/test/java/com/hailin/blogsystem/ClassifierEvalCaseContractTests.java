package com.hailin.blogsystem;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hailin.blogsystem.ai.eval.P3Sample;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 分类器评测集骨架的**离线**契约校验（5a，2026-10-01）。
 *
 * <p><b>为什么单独一个类</b>：评测集主体 {@link AiIntentClassifierIntegrationTests} 被
 * {@code @EnabledIfSystemProperty(runRealLlm=true)} **整类禁用**——离线时它连 JSON 都不会读。
 * 于是"字段名拼错""record 没同步扩"这类错误只会在跑在线门（**真花 LLM 额度**）时才炸出来，
 * 而那时已经付过钱了。
 *
 * <p>这里把这件事提前到离线：JSON 能否反序列化成 record，本身就是"评测集契约是否对齐"的判定。
 * Jackson 默认 {@code FAIL_ON_UNKNOWN_PROPERTIES=true}，所以 JSON 里多写一个字段名也会被抓住。
 */
class ClassifierEvalCaseContractTests {

    private static final String EVAL_CASES = "/agent/classifier-evaluation-cases.json";
    private static final String P3_SAMPLES = "/agent/classifier-p3-spike-samples.json";
    private static final String FIVE_A_CATEGORY = "5a-workflow-handoff";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private List<AiIntentClassifierIntegrationTests.ClassifierEvalCase> loadCases() throws Exception {
        try (InputStream in = getClass().getResourceAsStream(EVAL_CASES)) {
            assertThat(in).as("分类器评测集文件必须存在").isNotNull();
            return MAPPER.readValue(Objects.requireNonNull(in), new TypeReference<>() {
            });
        }
    }

    @Test
    void evalCaseJsonDeserializesIntoRecord() throws Exception {
        List<AiIntentClassifierIntegrationTests.ClassifierEvalCase> cases = loadCases();

        assertThat(cases)
                .as("评测集不能为空")
                .isNotEmpty();
        // 每例必须能定位失败——没有 name / message 的样例在报告里就是一团乱码
        assertThat(cases).allSatisfy(c -> {
            assertThat(c.name()).as("样例缺少 name").isNotBlank();
            assertThat(c.message()).as("样例 %s 缺少 message", c.name()).isNotBlank();
        });
    }

    @Test
    void p3SpikeSamplesDeserializeAndCoverAllLayers() throws Exception {
        /*
         * P3.0 的样例同样要在**离线**验证：采集类被 @EnabledIfSystemProperty 挡着，
         * 离线时连 JSON 都不会读——字段写错只会在真花钱跑那一次才暴露。
         *
         * 另外这里锁"分层覆盖"：P3 的结论建立在分层对比上（哪一层被省、哪一层被拖），
         * 层缺了会得出片面结论而不自知。
         */
        List<P3Sample> samples;
        try (InputStream in = getClass().getResourceAsStream(P3_SAMPLES)) {
            assertThat(in).as("P3 spike 样例文件必须存在").isNotNull();
            samples = MAPPER.readValue(Objects.requireNonNull(in), new TypeReference<>() {
            });
        }

        assertThat(samples)
                .as("P3 分层筛选的样本量")
                .hasSizeGreaterThanOrEqualTo(15);
        assertThat(samples).allSatisfy(s -> {
            assertThat(s.name()).isNotBlank();
            assertThat(s.layer()).isNotBlank();
            // message 允许为空：绑定评测集的样例从那边取消息（零重复），不在这里再写一份
        });
        assertThat(samples.stream().map(P3Sample::layer).distinct().toList())
                .as("八层都要有——否则 P3 的对比结论会漏掉一整类请求")
                .contains("chat", "article-qa", "action", "composite",
                        "workflow-single", "workflow-composite", "learning", "page-action",
                        "plan-locating");

        /*
         * spike 样例引用评测集（零重复）：消息与期望值都从那边取。
         * 所以"引用的名字真的存在"必须离线校验——打错一个名字，会在花过钱的采集时才炸。
         */
        List<String> evalCaseNames = loadCases().stream()
                .map(AiIntentClassifierIntegrationTests.ClassifierEvalCase::name)
                .toList();
        List<P3Sample> bound = samples.stream().filter(s -> s.evalCaseName() != null).toList();
        assertThat(bound)
                .as("大部分 spike 样例要绑定评测集硬门，否则漏目标的方案可能凭低 Token 获胜")
                .hasSizeGreaterThanOrEqualTo(15);
        assertThat(bound).allSatisfy(s -> assertThat(evalCaseNames)
                .as("spike 样例 %s 引用的评测集样例不存在", s.name())
                .contains(s.evalCaseName()));

        // 不引用评测集的样例必须自带 message（计划定位那两条依赖 fixture，无法进评测集）
        assertThat(samples.stream().filter(s -> s.evalCaseName() == null).toList())
                .allSatisfy(s -> assertThat(s.message())
                        .as("未绑定评测集的 spike 样例 %s 必须自带 message", s.name())
                        .isNotBlank());
    }

    @Test
    void evalCaseNamesAreUnique() throws Exception {
        List<String> names = loadCases().stream()
                .map(AiIntentClassifierIntegrationTests.ClassifierEvalCase::name)
                .toList();

        assertThat(names)
                .as("重名会让 -DevalCase=子串 过滤和失败定位都不可靠")
                .doesNotHaveDuplicates();
    }

    @Test
    void fiveAEvalCasesPendingFlagIsAllOrNothing() throws Exception {
        /*
         * 5a 采用"样例先落库、prompt 后改"的顺序，所以这批样例带一个 pendingUntilPromptReady 开关：
         * 标记为 true 时跳过（prompt 还没声明 WORKFLOW_HANDOFF），激活后整批一起跑。
         *
         * 这里锁的是"**整体一致**"：要么全标、要么全不标。混着最危险——
         * 在线门会跑一半跳一半，报告显示"全绿"却没测几条，人会误以为契约已经验证过了。
         */
        List<AiIntentClassifierIntegrationTests.ClassifierEvalCase> fiveA = loadCases().stream()
                .filter(c -> FIVE_A_CATEGORY.equals(c.category()))
                .toList();

        assertThat(fiveA)
                .as("5a 样例集不能是空的——它承载 WORKFLOW_HANDOFF 的双写契约")
                .isNotEmpty();

        long pendingCount = fiveA.stream()
                .filter(c -> Boolean.TRUE.equals(c.pendingUntilPromptReady()))
                .count();
        assertThat(pendingCount)
                .as("5a 样例要么全部 pending、要么全部激活，不能一半一半")
                .isIn(0L, (long) fiveA.size());

        // 双写契约：正例必须同时断言新协议与旧字段，只测一半等于没测
        List<AiIntentClassifierIntegrationTests.ClassifierEvalCase> dualWriteCases = fiveA.stream()
                .filter(c -> c.expectedTaskGoalWorkflowTypesContains() != null)
                .toList();
        assertThat(dualWriteCases)
                .as("至少要有一批样例同时断言新旧两套字段")
                .isNotEmpty()
                .allSatisfy(c -> {
                    List<String> workflowTypes = c.expectedTaskGoalWorkflowTypesContains();
                    if (workflowTypes.size() == 1) {
                        assertThat(c.expectedSuggestedWorkflowType())
                                .as("样例 %s：单 Workflow 诉求必须锁住旧字段双写", c.name())
                                .isEqualTo(workflowTypes.get(0));
                    } else {
                        assertThat(c.expectedSuggestedWorkflowTypeBlank())
                                .as("样例 %s：多 Workflow 诉求必须锁住旧单值字段为空", c.name())
                                .isTrue();
                    }
                });
    }
}
