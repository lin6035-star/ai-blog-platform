package com.hailin.blogsystem;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hailin.blogsystem.ai.TokenUsageAccumulator;
import com.hailin.blogsystem.ai.agent.AgentRunResult;
import com.hailin.blogsystem.ai.agent.AgentStepEmitter;
import com.hailin.blogsystem.ai.agent.ArticleAgentRuntime;
import com.hailin.blogsystem.entity.AiAgentRun;
import com.hailin.blogsystem.entity.AiAgentStep;
import com.hailin.blogsystem.entity.Articles;
import com.hailin.blogsystem.entity.dto.AiIntent;
import com.hailin.blogsystem.entity.dto.PageContextDTO;
import com.hailin.blogsystem.mapper.AiAgentRunMapper;
import com.hailin.blogsystem.mapper.AiAgentStepMapper;
import com.hailin.blogsystem.mapper.ArticlesMapper;
import com.hailin.blogsystem.service.AiIntentClassifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 文章 Agent Token 基线采集（Token 工程 P0-b，2026-09-30）。
 *
 * <p><b>为什么要有它</b>：Token 工程后续几刀（分类器收缩 / AgentState / 动态动作集合）都要回答
 * 「省了多少」，而没有基线的对比就是凭感觉。本类用固定问题跑真实文章 Agent，把
 * <b>路由 / run / step 三级 Token</b> 落成可重复采集的文件。
 *
 * <p><b>它同时是 P0-b 落库口径的端到端验证</b>：采集的数据全部从数据库读回
 * （{@code ai_agent_runs} / {@code ai_agent_steps}），而不是取内存返回值——
 * 落库错了这里就会看到 0。
 *
 * <p><b>步数不可控，所以不按"2/3/4 步"预设问题</b>：Agent 走几步由模型当场决定，
 * 本类跑固定问题后<b>按实际 usedSteps 分档统计</b>。样本少时某档为空是正常的，
 * 补样本靠加问题，不靠调参。
 *
 * <p>跑法（**会真实消耗 LLM 额度**，约 12~15 次调用）：
 * <pre>mvnw test -Dtest=AgentTokenBaselineTests -DrunRealLlm=true</pre>
 * 结果写到 {@code target/agent-token-baseline.txt}，由人整理进设计稿/报告。
 */
@Tag("eval")
@SpringBootTest
@EnabledIfSystemProperty(named = "runRealLlm", matches = "true")
class AgentTokenBaselineTests {

    @Autowired
    private ArticleAgentRuntime articleAgentRuntime;

    @Autowired
    private AiIntentClassifier aiIntentClassifier;

    @Autowired
    private ArticlesMapper articlesMapper;

    @Autowired
    private AiAgentRunMapper runMapper;

    @Autowired
    private AiAgentStepMapper stepMapper;

    /** mock 掉真 ES vectorStore（与评测门同规矩：基线不依赖本地 ES 可用性） */
    @MockBean
    private VectorStore vectorStore;

    private static final Path OUTPUT = Path.of("target", "agent-token-baseline.txt");

    /** 夹具作者：data.sql 里的 users 种子 id=100（分析路径要求传真实用户） */
    private static final Long FIXTURE_AUTHOR_ID = 100L;

    /** 夹具标题：清理时按它精确定位，避免误删业务数据 */
    private static final String FIXTURE_TITLE = "Token 基线夹具：缓存工程化长文";

    /**
     * 固定问题集。
     *
     * <p>前两句取自分类器评测集里已锁定为 {@code ARTICLE_AGENT} 的样例（多子目标），
     * 第三句是单目标对照——它可能被判成 QA 而不进 Agent，**记下 intent 由人工判断是否计入基线**。
     */
    private static final List<String> MESSAGES = List.of(
            "先看整体结构，再看看缓存击穿那一节",
            "对比一下缓存击穿和缓存穿透这两节的写法，说说哪节写得更好，再结合我的写作偏好给建议",
            "这篇写得怎么样"
    );

    @Test
    void collectArticleAgentTokenBaseline() throws Exception {
        // 自造长文夹具——测试库的种子文章正文只有十几个字符，测不出 observation 重放
        Articles article = createLongArticleFixture();

        PageContextDTO pageContext = new PageContextDTO();
        pageContext.setPageType("article-detail");
        pageContext.setArticleId(String.valueOf(article.getId()));
        pageContext.setAuthorId(String.valueOf(article.getAuthorId()));

        StringBuilder report = new StringBuilder();
        report.append("===== 文章 Agent Token 基线（2026-09-30，Token 工程 P0-b）=====\n");
        report.append("采集条件：articleId=").append(article.getId())
                .append("《").append(article.getTitle()).append("》")
                .append(" | 正文=").append(article.getContent().length()).append(" 字符（自造夹具）")
                .append(" | userId=").append(article.getAuthorId())
                .append(" | 真实 LLM | 数据从 DB 读回\n");

        List<Sample> samples = new ArrayList<>();
        int index = 0;
        for (String message : MESSAGES) {
            index++;
            samples.add(collectOne(index, message, article, pageContext, report));
        }

        appendSummary(samples, report);

        Files.createDirectories(OUTPUT.getParent());
        Files.writeString(OUTPUT, report.toString(), StandardCharsets.UTF_8);
        System.out.println(report);
    }

    private Sample collectOne(int index, String message, Articles article,
                              PageContextDTO pageContext, StringBuilder report) {
        report.append("\n--- 样本 ").append(index).append("：").append(message).append('\n');

        // ① 路由成本：真实走一次分类器（这条消息实际会付的路由开销）
        TokenUsageAccumulator routeUsage = new TokenUsageAccumulator();
        AiIntent intent;
        try {
            intent = aiIntentClassifier.classify(message, pageContext, article.getAuthorId(), routeUsage);
        } catch (RuntimeException e) {
            report.append("  分类器调用失败（基础设施问题，不计入基线）：")
                    .append(e.getMessage()).append('\n');
            return Sample.failed(index, message);
        }
        report.append("  分类器：intent=").append(intent == null ? "null" : intent.getIntent())
                .append(" | requestKind=").append(intent == null ? "-" : intent.getRequestKind())
                .append(" | routing tokens=").append(routeUsage.getTotalTokens()).append('\n');

        // ② Agent run（noop emitter：基线只关心 Token，不关心 SSE）
        AgentRunResult result;
        try {
            result = articleAgentRuntime.run(
                    article.getAuthorId(), null, message, pageContext, AgentStepEmitter.noop());
        } catch (RuntimeException e) {
            report.append("  Agent 执行异常（不计入基线）：").append(e.getMessage()).append('\n');
            return Sample.failed(index, message);
        }

        // ③ 从 DB 读回——落库口径错了这里就会看到 0（本类兼作 P0-b 的端到端验证）
        AiAgentRun savedRun = runMapper.selectById(result.agentRunId());
        List<AiAgentStep> steps = stepMapper.selectList(
                new LambdaQueryWrapper<AiAgentStep>()
                        .eq(AiAgentStep::getAgentRunId, result.agentRunId())
                        .orderByAsc(AiAgentStep::getStepNo));

        int runIn = savedRun == null || savedRun.getInputTokens() == null ? 0 : savedRun.getInputTokens();
        int runOut = savedRun == null || savedRun.getOutputTokens() == null ? 0 : savedRun.getOutputTokens();
        int runTotal = savedRun == null || savedRun.getTotalTokens() == null ? 0 : savedRun.getTotalTokens();

        report.append("  Run：runId=").append(result.agentRunId())
                .append(" | status=").append(result.status())
                .append(" | 实际步数=").append(result.usedSteps()).append('\n');
        report.append("  Run token（DB 读回）：in=").append(runIn)
                .append(" out=").append(runOut)
                .append(" total=").append(runTotal).append('\n');
        report.append("  Step 明细（输入随步递增 = 每步重发累计 observation，这正是要治理的重放）：\n");

        int stepSum = 0;
        for (AiAgentStep step : steps) {
            int in = step.getInputTokens() == null ? 0 : step.getInputTokens();
            int out = step.getOutputTokens() == null ? 0 : step.getOutputTokens();
            stepSum += in;
            report.append("    #").append(step.getStepNo())
                    .append(' ').append(step.getActionType())
                    .append("  in=").append(in)
                    .append(" out=").append(out)
                    .append(' ').append(step.getStatus()).append('\n');
        }

        int routeTokens = routeUsage.getTotalTokens();
        report.append("  端到端输入（路由 + run）= ").append(routeTokens + runIn).append('\n');

        Sample sample = new Sample(index, message, true, result.usedSteps(), routeTokens, runIn, runOut, runTotal, stepSum);
        return sample;
    }

    /** 按实际步数分档——步数由模型当场决定，不能预设 */
    private void appendSummary(List<Sample> samples, StringBuilder report) {
        report.append("\n===== 汇总（按实际步数分档）=====\n");
        Map<Integer, List<Sample>> bySteps = new LinkedHashMap<>();
        for (Sample sample : samples) {
            if (!sample.ok()) {
                continue;
            }
            bySteps.computeIfAbsent(sample.steps(), k -> new ArrayList<>()).add(sample);
        }
        if (bySteps.isEmpty()) {
            report.append("  无可计入的样本（全部失败或被跳过）\n");
            return;
        }
        for (Map.Entry<Integer, List<Sample>> entry : bySteps.entrySet()) {
            List<Sample> group = entry.getValue();
            int avgRunIn = (int) group.stream().mapToInt(Sample::runIn).average().orElse(0);
            int avgRoute = (int) group.stream().mapToInt(Sample::routeTokens).average().orElse(0);
            int avgStepIn = (int) group.stream().mapToInt(Sample::stepSumIn).average().orElse(0);
            report.append("  ").append(entry.getKey()).append(" 步：")
                    .append(group.size()).append(" 个样本")
                    .append(" | 平均 run 输入=").append(avgRunIn)
                    .append(" | 平均路由=").append(avgRoute)
                    .append(" | 平均端到端输入=").append(avgRunIn + avgRoute)
                    .append(" | 其中 step 决策输入合计均值=").append(avgStepIn)
                    .append('\n');
        }
        report.append("\n注：step 决策输入合计 / run 输入 ≈ 决策链占比；差额来自 Verifier 与到顶 summarize。\n");
    }

    /**
     * 自造长文夹具——**不能依赖测试库的种子文章**。
     *
     * <p>2026-09-30 首轮实测教训：测试跑在 H2 内存库上（见 `src/test/resources/application.properties`），
     * 种子文章《Published Article》正文只有 17 字符 → QUERY_ARTICLE 的 observation 极短 →
     * 每步决策输入恒定在 ~2400 token，<b>「累计重放」这个要治理的核心问题被静态 prompt 完全淹没</b>。
     * observation 长度取决于文章结构与正文体量，所以夹具必须贴近真实长文（开发库里的长篇约 1.3 万字符）。
     *
     * <p>用夹具而非库里现成数据还有两个好处：长度可控、每次跑完全一致（基线要能重复对比）。
     */
    private Articles createLongArticleFixture() {
        StringBuilder content = new StringBuilder();
        for (int section = 1; section <= 10; section++) {
            content.append("## 第").append(section).append("节：缓存问题的第").append(section).append("个侧面\n\n");
            for (int para = 1; para <= 20; para++) {
                content.append("这是第").append(section).append("节第").append(para)
                        .append("段。讨论缓存穿透、击穿、雪崩与一致性的处理方式，")
                        .append("以及工程上为什么需要互斥回源、逐 key TTL 与延迟双删。\n\n");
            }
        }

        Articles article = new Articles();
        article.setCategoryId(1L);
        article.setAuthorId(FIXTURE_AUTHOR_ID);
        article.setTitle(FIXTURE_TITLE);
        article.setSummary("基线夹具（非业务数据）");
        article.setContent(content.toString());
        article.setStatus(1);
        article.setViewCount(0);
        article.setPublishedAt(LocalDateTime.now());
        article.setCreatedAt(LocalDateTime.now());
        article.setUpdatedAt(LocalDateTime.now());
        articlesMapper.insert(article);
        return article;
    }

    /** 夹具必须清理：H2 内存库在同一 JVM 内被多个测试类共享（DB_CLOSE_DELAY=-1），残留会影响别的用例 */
    @AfterEach
    void cleanFixture() {
        articlesMapper.delete(new LambdaQueryWrapper<Articles>()
                .eq(Articles::getTitle, FIXTURE_TITLE));
    }

    private record Sample(int index, String message, boolean ok, int steps,
                          int routeTokens, int runIn, int runOut, int runTotal, int stepSumIn) {
        static Sample failed(int index, String message) {
            return new Sample(index, message, false, 0, 0, 0, 0, 0, 0);
        }
    }
}
