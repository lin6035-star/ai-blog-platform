package com.hailin.blogsystem;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hailin.blogsystem.ai.AiJudgeModelSupport;
import com.hailin.blogsystem.ai.ClassifierProbe;
import com.hailin.blogsystem.ai.TokenUsageAccumulator;
import com.hailin.blogsystem.ai.eval.ClassifierMetrics;
import com.hailin.blogsystem.ai.eval.P3Sample;
import com.hailin.blogsystem.entity.LearningPlans;
import com.hailin.blogsystem.entity.dto.AiIntent;
import com.hailin.blogsystem.entity.dto.PageContextDTO;
import com.hailin.blogsystem.mapper.LearningPlanMapper;
import com.hailin.blogsystem.service.AiIntentClassifier;
import com.hailin.blogsystem.service.LearningPlansService;
import com.hailin.blogsystem.service.impl.ClassifierCandidate;
import com.hailin.blogsystem.service.impl.ClassifierCandidateRunner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P3.0：分类器**基线与候选方案的同口径测量**（当前实现）。
 *
 * <p><b>它不参与评测门断言</b>：这里采数字——Token、调用次数与角色、推理/缓存、延迟、落点。
 * 所以刻意**不标 {@code @Tag("eval")}**：否则 {@code -Dgroups=eval} 会顺手跑它，
 * 既多花钱、又把"测量"和"断言"混成一份报告，失败时看不出是"判错了"还是"变慢了"。
 *
 * <p><b>硬门（关键）</b>：样例大多**引用评测集**（{@code evalCaseName}），消息与期望值都从那边取，
 * 并跑与在线门**同一套**契约断言。硬门不过的样例在报告里标 {@code FAIL}，
 * <b>不得参与性能结论</b>——否则漏掉第二目标的方案可能凭低 Token 获胜。
 *
 * <p>跑法（单独跑，不问在线门）：
 * <pre>
 * .\mvnw.cmd test "-Dtest=ClassifierP3BaselineTests" "-DrunRealLlm=true"
 * </pre>
 */
@SpringBootTest
@EnabledIfSystemProperty(named = "runRealLlm", matches = "true")
class ClassifierP3BaselineTests {

    @Autowired
    private AiIntentClassifier aiIntentClassifier;

    @Autowired
    private LearningPlanMapper learningPlanMapper;

    /** mock 掉真 ES vectorStore：测量的是分类器，不该依赖本地 ES 可用性 */
    @MockBean
    private VectorStore vectorStore;

    private static final Path OUTPUT = Path.of("target", "classifier-p3-baseline.txt");
    private static final String SAMPLES = "/agent/classifier-p3-spike-samples.json";
    private static final String EVAL_CASES = "/agent/classifier-evaluation-cases.json";
    /** 计划定位 fixture 的用户：与在线门共用 109，避开其他测试类占用的 ID */
    private static final Long PLAN_USER = 109L;

    /**
     * 样例之间的退避间隔（{@code -Dp3ThrottleMs=8000}）。
     *
     * <p>供应商对本账号有 token 速率上限（实测：密集跑 13 条 ≈ 109k 输入 token 后开始批量 429
     * {@code insufficient_quota}，等待约半分钟后恢复）。**退避不计入下面的耗时统计**——
     * 我们要的是模型延迟，不是我们的等待时间。
     */
    private static final long THROTTLE_MS = Long.getLong("p3ThrottleMs", 0L);

    // —— 配对测量（collectCandidatePairs）用到的依赖：只为搭出与生产同构的测试侧链路 ——

    @Autowired
    private ChatClient.Builder chatClientBuilder;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private LearningPlansService learningPlansService;

    @Autowired
    private AiJudgeModelSupport aiJudgeModelSupport;

    private static final Path PAIRS_OUTPUT = Path.of("target", "classifier-p3-pairs.txt");

    /** 工程收益门槛（Codex 定）：配对输入降幅低于它，候选就不值得继续做 */
    private static final double MIN_PAIR_REDUCTION_PCT = 15.0;

    @Test
    void collectClassifierBaseline() throws Exception {
        List<P3Sample> spikes = load(SAMPLES, new TypeReference<>() {
        });
        Map<String, AiIntentClassifierIntegrationTests.ClassifierEvalCase> evalCases = new LinkedHashMap<>();
        for (AiIntentClassifierIntegrationTests.ClassifierEvalCase c
                : load(EVAL_CASES, new TypeReference<List<AiIntentClassifierIntegrationTests.ClassifierEvalCase>>() {
        })) {
            evalCases.put(c.name(), c);
        }

        // 缩样：只跑名字匹配的样例（-Dp3Sample=子串，支持逗号分隔多个）——定点复跑时不必重跑全部
        String filter = System.getProperty("p3Sample");
        spikes = narrow(spikes, filter);

        List<ClassifierMetrics.Sample> metrics = new ArrayList<>();

        boolean first = true;
        for (P3Sample spike : spikes) {
            if (THROTTLE_MS > 0 && !first) {
                Thread.sleep(THROTTLE_MS);
            }
            first = false;
            // 绑定评测集的样例：消息与上下文都从评测集取，保证"测的"与"约束的"是同一条
            AiIntentClassifierIntegrationTests.ClassifierEvalCase evalCase =
                    spike.evalCaseName() == null ? null : evalCases.get(spike.evalCaseName());
            String message = evalCase != null ? evalCase.message() : spike.message();
            String pageType = evalCase != null ? evalCase.pageType() : spike.pageType();
            String pageArticleId = evalCase != null ? evalCase.pageArticleId() : spike.pageArticleId();

            if (spike.planFixtureTitles() != null) {
                spike.planFixtureTitles().forEach(this::insertActivePlan);
            }

            PageContextDTO pageContext = null;
            if (pageType != null) {
                pageContext = new PageContextDTO();
                pageContext.setPageType(pageType);
                pageContext.setArticleId(pageArticleId);
            }

            TokenUsageAccumulator usage = new TokenUsageAccumulator();
            ClassifierProbe probe = new ClassifierProbe();
            long start = System.nanoTime();
            // 失败不再靠 null 判断：生产分类器失败会返回 generalChat()，落点看 probe.getOutcome()
            AiIntent intent = aiIntentClassifier.classify(message, pageContext, spike.userId(), usage, probe);
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;

            GateResult gate = runHardGate(spike, evalCase, message, intent);

            metrics.add(new ClassifierMetrics.Sample(
                    spike.name(), spike.layer(), probe.getHttpCallCount(),
                    usage.getPromptTokens(), usage.getCompletionTokens(), usage.getTotalTokens(),
                    usage.getReasoningTokens(), usage.getCachedTokens(), probe.getResponseChars(),
                    elapsedMs, probe.getOutcome().name(), rolesOf(probe),
                    gate.status(), gate.detail()));

            System.out.printf("[P3-BASELINE] %-26s calls=%d prompt=%d out=%d reason=%d chars=%d %dms %s gate=%s roles=%s%n",
                    spike.name(), probe.getHttpCallCount(), usage.getPromptTokens(),
                    usage.getCompletionTokens(), usage.getReasoningTokens(),
                    probe.getResponseChars(), elapsedMs, probe.getOutcome(), gate.status(), rolesOf(probe));
        }

        Files.createDirectories(OUTPUT.getParent());
        Files.writeString(OUTPUT, ClassifierMetrics.report(metrics,
                        "P3.0 分类器基线（当前实现）"
                                + (filter == null || filter.isBlank() ? "" : "｜缩样：" + filter)),
                StandardCharsets.UTF_8);
        System.out.println("[P3-BASELINE] 报告已写入 " + OUTPUT.toAbsolutePath());
    }

    /**
     * P3 候选**配对测量**：同一条样例跑 FULL（现状）与候选 A，比的是**配对差值**。
     *
     * <p><b>为什么不先跑完 A 再跑 FULL</b>：两批之间隔着几十分钟，供应商负载、限流状态、
     * 模型侧时段波动都会变——那不是候选的差异，却会被算进差值里。所以逐条交错：
     * 偶数条 FULL→A、奇数条 A→FULL，顺序效应不落在任何一个候选头上，差值只剩 prompt 的贡献。
     *
     * <p><b>这一轮只用于淘汰</b>（小样本只用于淘汰）：不下"上线"结论，只回答一个问题——
     * 候选 A 值不值得继续做。而且硬门先于性能：A 有任何一条判错，就不再看它省了多少 Token。
     *
     * <p>跑法：
     * <pre>
     * .\mvnw.cmd test "-Dtest=ClassifierP3BaselineTests#collectCandidatePairs" "-DrunRealLlm=true" "-Dp3ThrottleMs=8000"
     * </pre>
     */
    @Test
    void collectCandidatePairs() throws Exception {
        List<P3Sample> spikes = load(SAMPLES, new TypeReference<>() {
        });
        Map<String, AiIntentClassifierIntegrationTests.ClassifierEvalCase> evalCases = new LinkedHashMap<>();
        for (AiIntentClassifierIntegrationTests.ClassifierEvalCase c
                : load(EVAL_CASES, new TypeReference<List<AiIntentClassifierIntegrationTests.ClassifierEvalCase>>() {
        })) {
            evalCases.put(c.name(), c);
        }

        String filter = System.getProperty("p3Sample");
        spikes = narrow(spikes, filter);

        ClassifierCandidateRunner runner = new ClassifierCandidateRunner(
                chatClientBuilder, objectMapper, learningPlansService, aiJudgeModelSupport);
        ClassifierCandidate[] pair = candidates();
        ClassifierCandidate left = pair[0];
        ClassifierCandidate right = pair[1];

        List<PairRow> rows = new ArrayList<>();
        for (int i = 0; i < spikes.size(); i++) {
            P3Sample spike = spikes.get(i);
            AiIntentClassifierIntegrationTests.ClassifierEvalCase evalCase =
                    spike.evalCaseName() == null ? null : evalCases.get(spike.evalCaseName());
            String message = evalCase != null ? evalCase.message() : spike.message();
            String pageType = evalCase != null ? evalCase.pageType() : spike.pageType();
            String pageArticleId = evalCase != null ? evalCase.pageArticleId() : spike.pageArticleId();

            // 计划 fixture 在**两侧之前**插入一次：两个候选必须看到同一份计划列表
            if (spike.planFixtureTitles() != null) {
                spike.planFixtureTitles().forEach(this::insertActivePlan);
            }
            PageContextDTO pageContext = null;
            if (pageType != null) {
                pageContext = new PageContextDTO();
                pageContext.setPageType(pageType);
                pageContext.setArticleId(pageArticleId);
            }

            // 条内交替：偶数条"左→右"、奇数条"右→左"，顺序效应不落在任何一个候选头上
            ClassifierCandidate[] order = i % 2 == 0
                    ? new ClassifierCandidate[]{left, right}
                    : new ClassifierCandidate[]{right, left};

            Map<String, CandidateRun> runs = new LinkedHashMap<>();
            for (ClassifierCandidate candidate : order) {
                throttle();
                runs.put(candidate.id(), runCandidate(runner, candidate, spike, evalCase, message,
                        pageContext, spike.userId()));
            }

            PairRow row = new PairRow(spike.name(), spike.layer(), runs.get(left.id()), runs.get(right.id()));
            rows.add(row);
            System.out.printf("[P3-PAIR] %-26s %s in=%-6d gate=%-4s | %s in=%-6d gate=%-4s%n",
                    spike.name(), left.id(), row.left().promptTokens(), row.left().gate(),
                    right.id(), row.right().promptTokens(), row.right().gate());
        }

        Files.createDirectories(PAIRS_OUTPUT.getParent());
        Files.writeString(PAIRS_OUTPUT, pairsReport(rows, filter, left, right), StandardCharsets.UTF_8);
        System.out.println("[P3-PAIR] 报告已写入 " + PAIRS_OUTPUT.toAbsolutePath());

        // 采集完整性：每条样例的两侧都必须有结果。中途被 429 打挂时最容易出现的假象，
        // 就是"少了一条"被汇总当成"省了一条"
        assertThat(rows).hasSize(spikes.size());
        for (PairRow row : rows) {
            assertThat(row.left()).as(row.name() + " 缺左侧结果").isNotNull();
            assertThat(row.right()).as(row.name() + " 缺右侧结果").isNotNull();
        }
    }

    /**
     * 缩样：{@code -Dp3Sample=子串}，支持**逗号分隔多个**子串（任一命中即保留）。
     *
     * <p>为什么支持多个：一次改动的影响面往往是一条明确的线（"所有含 WORKFLOW_HANDOFF 的样例"
     * 或"所有 Workflow 类"），一个子串串不起来；而为了验证一条线去跑全量，代价差一个数量级。
     *
     * <p>空匹配直接断言失败——缩样写错时**宁可报错，也不能静默跑全量**（那是最贵的失败方式）。
     */
    private static List<P3Sample> narrow(List<P3Sample> spikes, String filter) {
        if (filter == null || filter.isBlank()) {
            return spikes;
        }
        List<String> keys = Arrays.stream(filter.split(","))
                .map(String::trim)
                .filter(key -> !key.isEmpty())
                .toList();
        List<P3Sample> narrowed = spikes.stream()
                .filter(spike -> keys.stream().anyMatch(spike.name()::contains))
                .toList();
        assertThat(narrowed).as("-Dp3Sample=" + filter + " 无匹配样例").isNotEmpty();
        return narrowed;
    }

    /**
     * 配对的两个候选：{@code -Dp3Candidates=FULL,ON_DEMAND}（默认，即 A 对现状）；
     * A/B 对照用 {@code -Dp3Candidates=ON_DEMAND,ROUTE_EXTRACTOR}。
     *
     * <p>写成参数而不是写死，是因为 P3 要比的**不止一对**：先 A 对现状、再 B 对 A。
     * 每次改代码去改常量，迟早会出现"报告头写着 FULL vs A、实际跑的是别的一对"。
     */
    private static ClassifierCandidate[] candidates() {
        String raw = System.getProperty("p3Candidates");
        if (raw == null || raw.isBlank()) {
            return new ClassifierCandidate[]{ClassifierCandidate.FULL, ClassifierCandidate.ON_DEMAND};
        }
        String[] names = Arrays.stream(raw.split(",")).map(String::trim).toArray(String[]::new);
        assertThat(names).as("-Dp3Candidates 需要两个候选，用逗号分隔，例如 FULL,ON_DEMAND").hasSize(2);
        try {
            return new ClassifierCandidate[]{
                    ClassifierCandidate.valueOf(names[0].toUpperCase(Locale.ROOT)),
                    ClassifierCandidate.valueOf(names[1].toUpperCase(Locale.ROOT))};
        } catch (IllegalArgumentException e) {
            throw new AssertionError("-Dp3Candidates 里有未登记的候选：" + raw
                    + "（可选 " + Arrays.toString(ClassifierCandidate.values()) + "）", e);
        }
    }

    /** 候选之间退避：交错后调用密度翻倍，429 来得更早（实测密集跑 13 条约 109k 输入 token 后开始限流） */
    private void throttle() throws InterruptedException {
        if (THROTTLE_MS > 0) {
            Thread.sleep(THROTTLE_MS);
        }
    }

    private CandidateRun runCandidate(ClassifierCandidateRunner runner, ClassifierCandidate candidate,
                                      P3Sample spike,
                                      AiIntentClassifierIntegrationTests.ClassifierEvalCase evalCase,
                                      String message, PageContextDTO pageContext, Long userId) {
        TokenUsageAccumulator usage = new TokenUsageAccumulator();
        ClassifierProbe probe = new ClassifierProbe();
        long start = System.nanoTime();
        AiIntent intent = runner.run(candidate, message, pageContext, userId, usage, probe);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        GateResult gate = runHardGate(spike, evalCase, message, intent);
        return new CandidateRun(candidate.id(), gate.status(), gate.detail(),
                usage.getPromptTokens(), usage.getCompletionTokens(),
                probe.getHttpCallCount(), probe.getOutcome().name(), rolesOf(probe), elapsedMs, brief(intent));
    }

    /**
     * 模型实际输出的关键字段——**失败时必须能看到它**。
     *
     * <p>硬门只留一句 {@code expected: "X" but was: null}，光凭它分不清是"漏判了目标"、
     * "判成了别的类型"，还是"字段没填"。这三种根因对应三种完全不同的修法，
     * 而每一次复现都要花额度——所以采集时就把输出留下来。
     */
    private static String brief(AiIntent intent) {
        if (intent == null) {
            return "intent=null";
        }
        return "intent=" + intent.getIntent()
                + " action=" + intent.getSuggestedAction()
                + " wf=" + intent.getSuggestedWorkflowType()
                + " kind=" + intent.getRequestKind()
                + " goals=" + goalsBrief(intent)
                + " evidence=" + intent.getAnalysisGoalEvidence()
                + " reason=" + intent.getReason();
    }

    /** 一个候选在一次样例上的原始结果——报告只做汇总，不在这里加工 */
    private record CandidateRun(String candidate, String gate, String gateDetail,
                                int promptTokens, int completionTokens, int calls,
                                String outcome, String roles, long elapsedMs, String brief) {
    }

    /** 同一条样例的两个候选并排：配对差值的最小单位是**这一对**，不是两批各自的均值 */
    private record PairRow(String name, String layer, CandidateRun left, CandidateRun right) {
    }

    private String pairsReport(List<PairRow> rows, String filter, ClassifierCandidate left, ClassifierCandidate right) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== P3 候选配对测量 ===\n");
        sb.append("左 = ").append(left.id()).append("　右 = ").append(right.id())
                .append("　缩样：").append(filter == null || filter.isBlank() ? "全量" : filter).append("\n");
        sb.append("口径：两侧走同一条测试侧同构链路（ClassifierCandidateRunner），差别只在候选方案本身。\n");
        sb.append("      条内顺序交替（偶数条 左→右、奇数条 右→左），两侧调用间退避 ")
                .append(THROTTLE_MS).append("ms。\n");
        sb.append("      **绝对值只在本表内可比**（测试侧链路 ≠ 生产链路），可相减的是配对差值。\n\n");

        sb.append(String.format("%-30s %9s %9s %8s %7s %7s %7s %8s %8s%n",
                "样例", truncate(left.id(), 9), truncate(right.id(), 9), "Δ输入", "Δ%",
                "左硬门", "右硬门", "左 ms", "右 ms"));
        for (PairRow r : rows) {
            long delta = r.left().promptTokens() - r.right().promptTokens();
            double pct = r.left().promptTokens() == 0 ? 0 : delta * 100.0 / r.left().promptTokens();
            sb.append(String.format("%-30s %9d %9d %8d %6.1f%% %7s %7s %8d %8d%n",
                    truncate(r.name(), 30), r.left().promptTokens(), r.right().promptTokens(),
                    delta, pct, r.left().gate(), r.right().gate(),
                    r.left().elapsedMs(), r.right().elapsedMs()));
        }

        // 只统计**两侧都没有硬门失败**的样例：省了 Token 但判错不是收益，是缺陷
        List<PairRow> paired = rows.stream()
                .filter(r -> !"FAIL".equals(r.left().gate()) && !"FAIL".equals(r.right().gate()))
                .toList();
        long leftIn = paired.stream().mapToLong(r -> r.left().promptTokens()).sum();
        long rightIn = paired.stream().mapToLong(r -> r.right().promptTokens()).sum();
        long leftOut = paired.stream().mapToLong(r -> r.left().completionTokens()).sum();
        long rightOut = paired.stream().mapToLong(r -> r.right().completionTokens()).sum();
        int leftCalls = paired.stream().mapToInt(r -> r.left().calls()).sum();
        int rightCalls = paired.stream().mapToInt(r -> r.right().calls()).sum();
        double reduction = leftIn == 0 ? 0 : (leftIn - rightIn) * 100.0 / leftIn;

        int leftPass = (int) rows.stream().filter(r -> "PASS".equals(r.left().gate())).count();
        int rightPass = (int) rows.stream().filter(r -> "PASS".equals(r.right().gate())).count();
        List<PairRow> rightDropped = rows.stream()
                .filter(r -> "FAIL".equals(r.right().gate()) && !"FAIL".equals(r.left().gate()))
                .toList();
        List<PairRow> leftDropped = rows.stream()
                .filter(r -> "FAIL".equals(r.left().gate()) && !"FAIL".equals(r.right().gate()))
                .toList();
        List<PairRow> bothFailed = rows.stream()
                .filter(r -> "FAIL".equals(r.right().gate()) && "FAIL".equals(r.left().gate()))
                .toList();

        sb.append(String.format("%n配对汇总（两侧均无硬门失败：%d / 共 %d 条）%n", paired.size(), rows.size()));
        sb.append(String.format("  输入 token     %s %d → %s %d      配对降幅 %.1f%%%n",
                left.id(), leftIn, right.id(), rightIn, reduction));
        sb.append(String.format("  输出 token     %s %d → %s %d%n", left.id(), leftOut, right.id(), rightOut));
        sb.append(String.format("  调用次数       %s %d → %s %d%n", left.id(), leftCalls, right.id(), rightCalls));
        sb.append(String.format("  延迟 P50/P95   %s %d/%d ms      %s %d/%d ms%n",
                left.id(),
                percentile(paired.stream().map(r -> r.left().elapsedMs()).toList(), 0.50),
                percentile(paired.stream().map(r -> r.left().elapsedMs()).toList(), 0.95),
                right.id(),
                percentile(paired.stream().map(r -> r.right().elapsedMs()).toList(), 0.50),
                percentile(paired.stream().map(r -> r.right().elapsedMs()).toList(), 0.95)));
        sb.append(String.format("  异常落点       %s %d 条         %s %d 条%n",
                left.id(), paired.stream().filter(r -> !"SUCCESS".equals(r.left().outcome())).count(),
                right.id(), paired.stream().filter(r -> !"SUCCESS".equals(r.right().outcome())).count()));
        sb.append(String.format("  硬门 PASS      %s %d/%d           %s %d/%d%n",
                left.id(), leftPass, rows.size(), right.id(), rightPass, rows.size()));

        appendDropped(sb, right.id() + " 掉门（" + left.id() + " 没掉）", rightDropped, left.id(), right.id(), true);
        appendDropped(sb, left.id() + " 掉门（" + right.id() + " 没掉）", leftDropped, left.id(), right.id(), false);
        if (!bothFailed.isEmpty()) {
            sb.append("\n两侧都掉门（样例本身不稳定，不作为淘汰证据）：\n");
            for (PairRow r : bothFailed) {
                sb.append("  - ").append(r.name()).append("\n");
            }
        }

        sb.append("\n逐条模型输出（复盘用：能看出旧字段兜底改写了什么、模型有没有幻觉出 WORKFLOW_HANDOFF）\n");
        for (PairRow r : rows) {
            sb.append("  ").append(r.name()).append("\n");
            sb.append("    ").append(left.id()).append("：").append(r.left().brief())
                    .append("  roles=").append(r.left().roles()).append("\n");
            sb.append("    ").append(right.id()).append("：").append(r.right().brief())
                    .append("  roles=").append(r.right().roles()).append("\n");
        }

        sb.append("\n结论：")
                .append(verdict(rows, leftPass, rightPass, reduction, left, right))
                .append("\n");
        return sb.toString();
    }

    private void appendDropped(StringBuilder sb, String title, List<PairRow> dropped,
                               String leftId, String rightId, boolean rightSide) {
        sb.append("\n").append(title).append("：\n");
        if (dropped.isEmpty()) {
            sb.append("  （无）\n");
            return;
        }
        for (PairRow r : dropped) {
            CandidateRun failed = rightSide ? r.right() : r.left();
            sb.append("  - ").append(r.name()).append("\n");
            sb.append("      硬门：").append(failed.gateDetail()).append("\n");
            sb.append("      ").append(leftId).append(" 输出：").append(r.left().brief()).append("\n");
            sb.append("      ").append(rightId).append(" 输出：").append(r.right().brief()).append("\n");
        }
    }

    /**
     * 胜负判据：**先正确性、后收益**。
     *
     * <p>只回答"这两个候选里该留哪个"，不回答"能不能上线"——上线要过完整在线门（64 例）并走 SHADOW。
     * 收益门槛用配对输入降幅；两侧差不到门槛时判"没有明显差距"，而不是硬选一个。
     *
     * <p>注意这里**不把"掉门"直接当淘汰**：模型输出本身不稳定（实测同一 prompt 同一 token
     * 数会有 40%~60% 概率漏字段），所以报告把"只有一侧掉门"如实列出，是否算稳定失败要靠复跑定性。
     */
    private static String verdict(List<PairRow> rows, int leftPass, int rightPass, double reduction,
                                  ClassifierCandidate left, ClassifierCandidate right) {
        if (rightPass < leftPass) {
            return String.format("淘汰 %s —— 硬门 %d/%d，低于 %s 的 %d/%d；正确性不过关就不再比收益",
                    right.id(), rightPass, rows.size(), left.id(), leftPass, rows.size());
        }
        if (rightPass > leftPass) {
            return String.format("%s 胜出 —— 硬门 %d/%d 高于 %s 的 %d/%d（正确性优先于收益）",
                    right.id(), rightPass, rows.size(), left.id(), leftPass, rows.size());
        }
        if (reduction >= MIN_PAIR_REDUCTION_PCT) {
            return String.format("%s 胜出 —— 硬门持平（%d/%d），且配对输入降幅 %.1f%% ≥ %.0f%%",
                    right.id(), rightPass, rows.size(), reduction, MIN_PAIR_REDUCTION_PCT);
        }
        if (reduction <= -MIN_PAIR_REDUCTION_PCT) {
            return String.format("%s 胜出 —— 硬门持平（%d/%d），但 %s 要多花 %.1f%% 输入；"
                            + "调用次数 %s %d vs %s %d（双调用踩在供应商限流这个真瓶颈上）",
                    left.id(), leftPass, rows.size(), right.id(), -reduction,
                    left.id(), rows.stream().mapToInt(r -> r.left().calls()).sum(),
                    right.id(), rows.stream().mapToInt(r -> r.right().calls()).sum());
        }
        return String.format("两者没有明显差距（硬门 %d/%d 持平，输入降幅差 %.1f%% < %.0f%%）"
                        + "——需要更多样本，或结合调用次数与 P95 再定",
                leftPass, rows.size(), Math.abs(reduction), MIN_PAIR_REDUCTION_PCT);
    }

    private static long percentile(List<Long> values, double p) {
        if (values.isEmpty()) {
            return 0;
        }
        List<Long> sorted = new ArrayList<>(values);
        sorted.sort(null);
        int idx = (int) Math.ceil(p * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(idx, sorted.size() - 1)));
    }

    private static String truncate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max - 1) + "…";
    }

    /**
     * P3 硬门：**正确性不过关的样例不进性能排名**。
     *
     * <p>复用在线门的 {@code assertWorkflowGoalContract}（5a 契约）而不是另写一份——
     * 两处各写一套，就会出现"候选 A 过得了这处、过不了那处"，而 P3 的排名建立在硬门之上。
     *
     * <p>断言失败**不抛出去**：采集要继续跑完，把失败如实写进报告（一条失败就中断，
     * 等于用一次抖动换掉整轮测量）。
     */
    private GateResult runHardGate(P3Sample spike,
                                   AiIntentClassifierIntegrationTests.ClassifierEvalCase evalCase,
                                   String message, AiIntent intent) {
        boolean hasGate = evalCase != null
                || spike.planIdMustBeBlank() != null || spike.planIdMustEqualTitle() != null;
        if (!hasGate) {
            return GateResult.none();
        }
        try {
            if (evalCase != null) {
                assertThat(intent).as("分类返回 null").isNotNull();
                AiIntentClassifierIntegrationTests.assertWorkflowGoalContract(evalCase.name(), message, intent);
                if (evalCase.expectedTaskGoalCountAtLeast() != null) {
                    List<Map<String, Object>> goals = intent.getTaskGoals();
                    assertThat(goals == null ? List.<Map<String, Object>>of() : goals)
                            .as("多目标完整性——少拆一个目标就是一次静默丢目标")
                            .hasSizeGreaterThanOrEqualTo(evalCase.expectedTaskGoalCountAtLeast());
                }
                if (evalCase.expectedTaskGoalTypesContains() != null) {
                    assertThat(goalTypes(intent)).containsAll(evalCase.expectedTaskGoalTypesContains());
                }
                if (evalCase.expectedTaskGoalTypesNotContains() != null) {
                    assertThat(goalTypes(intent))
                            .doesNotContainAnyElementsOf(evalCase.expectedTaskGoalTypesNotContains());
                }
                if (evalCase.expectedTaskGoalWorkflowTypesContains() != null) {
                    assertThat(AiIntentClassifierIntegrationTests.taskGoalWorkflowTypes(intent))
                            .containsAll(evalCase.expectedTaskGoalWorkflowTypesContains());
                }
                if (evalCase.expectedSuggestedWorkflowType() != null) {
                    assertThat(intent.getSuggestedWorkflowType())
                            .as("旧字段不能被新协议挤没——真正启动 Workflow 的仍是它")
                            .isEqualTo(evalCase.expectedSuggestedWorkflowType());
                }
            }
            if (Boolean.TRUE.equals(spike.planIdMustBeBlank())) {
                assertThat(intent == null ? null : intent.getLearningPlanId())
                        .as("未点名计划、多候选下不得猜——宁可留给后端追问")
                        .isNull();
            }
            if (spike.planIdMustEqualTitle() != null) {
                assertThat(intent == null ? null : intent.getLearningPlanId())
                        .as("点名了计划就要选中那一个")
                        .isEqualTo(planIdByTitle(spike.planIdMustEqualTitle()));
            }
            return GateResult.pass();
        } catch (AssertionError e) {
            // 不抛出：一条失败就中断整轮采集，等于用一次抖动换掉一次完整测量
            return GateResult.fail(e.getMessage());
        }
    }

    /** 硬门结果 + 失败原因（原因必须落进**报告文件**——控制台编码会把中文断言消息吃掉） */
    private record GateResult(String status, String detail) {

        static GateResult pass() {
            return new GateResult("PASS", null);
        }

        static GateResult none() {
            return new GateResult("NONE", null);
        }

        static GateResult fail(String detail) {
            return new GateResult("FAIL", detail);
        }
    }

    private static List<String> goalTypes(AiIntent intent) {
        return intent == null || intent.getTaskGoals() == null
                ? List.of()
                : intent.getTaskGoals().stream()
                .map(g -> g == null ? "" : String.valueOf(g.get("type")))
                .toList();
    }

    /**
     * taskGoals 的紧凑表示，**带上 workflowType**。
     *
     * <p>只打 type 不够：旧字段兜底是按 {@code params.workflowType} 推导的，
     * 复盘时必须一眼看出模型给的那条 WORKFLOW_HANDOFF 到底带了什么 workflowType
     * ——否则"兜底改了没改、改得对不对"都无从判断。
     */
    private static String goalsBrief(AiIntent intent) {
        if (intent == null || intent.getTaskGoals() == null) {
            return "[]";
        }
        return intent.getTaskGoals().stream().map(goal -> {
            Object type = goal == null ? null : goal.get("type");
            Object params = goal == null ? null : goal.get("params");
            String workflowType = params instanceof Map<?, ?> p && p.get("workflowType") != null
                    ? "(" + p.get("workflowType") + ")"
                    : "";
            return String.valueOf(type) + workflowType;
        }).toList().toString();
    }

    private static String rolesOf(ClassifierProbe probe) {
        return probe.getRoles().stream().map(Enum::name).reduce((a, b) -> a + "+" + b).orElse("-");
    }

    private void insertActivePlan(String title) {
        LearningPlans plan = new LearningPlans();
        plan.setUserId(PLAN_USER);
        plan.setTitle(title);
        plan.setStatus(LearningPlans.STATUS_ACTIVE);
        plan.setCreatedAt(LocalDateTime.now());
        plan.setUpdatedAt(LocalDateTime.now());
        learningPlanMapper.insert(plan);
    }

    private String planIdByTitle(String title) {
        LearningPlans plan = learningPlanMapper.selectOne(new LambdaQueryWrapper<LearningPlans>()
                .eq(LearningPlans::getUserId, PLAN_USER)
                .eq(LearningPlans::getTitle, title)
                .last("limit 1"));
        return plan == null ? null : String.valueOf(plan.getId());
    }

    @AfterEach
    void cleanupPlanFixture() {
        learningPlanMapper.delete(new LambdaQueryWrapper<LearningPlans>()
                .eq(LearningPlans::getUserId, PLAN_USER));
    }

    private <T> T load(String resource, TypeReference<T> type) throws Exception {
        try (InputStream in = getClass().getResourceAsStream(resource)) {
            assertThat(in).as("样例文件必须存在：" + resource).isNotNull();
            return new ObjectMapper().readValue(Objects.requireNonNull(in), type);
        }
    }
}
