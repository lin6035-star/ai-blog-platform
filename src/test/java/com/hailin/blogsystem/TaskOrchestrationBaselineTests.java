package com.hailin.blogsystem;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hailin.blogsystem.ai.TokenUsageAccumulator;
import com.hailin.blogsystem.ai.task.OrchestrationOutcome;
import com.hailin.blogsystem.ai.task.TaskOrchestrationService;
import com.hailin.blogsystem.ai.task.plan.TaskPlanningFacade;
import com.hailin.blogsystem.ai.task.plan.TaskRoute;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 复合任务 Token 基线（第四步·阶段 6，2026-09-30）。
 *
 * <p><b>为什么要有它</b>：Token 设计稿 §5.4 定的口径是「本层存储、按 {@code parent_run_id} 查询聚合」——
 * 单 run 的基线（{@code AgentTokenBaselineTests}）证明不了这条口径，
 * 因为它没有父子关系。本类跑**真的复合请求**（外层 TaskPlan + 内部 ReAct 子 run），
 * 验证两件事：
 * <ol>
 *   <li>「点赞 + 分析」确实走 ORCHESTRATE，外层两步 + 子 run 都留下了记录；</li>
 *   <li>Token 不重复也不漏：<b>所有 run 的 local 求和 == 子树聚合值</b>，且 message 口径可复算。</li>
 * </ol>
 *
 * <p>夹具与跑法同 {@code AgentTokenBaselineTests}：测试跑在 H2 内存库上，
 * 种子文章正文只有十几个字符，所以**自造长文**——observation 长度取决于文章体量，
 * 用短文测不出任何重放。
 *
 * <p>跑法（**会真实消耗 LLM 额度**，约 12~16 次调用）：
 * <pre>mvnw test -Dtest=TaskOrchestrationBaselineTests -DrunRealLlm=true</pre>
 */
@Tag("eval")
@SpringBootTest
@EnabledIfSystemProperty(named = "runRealLlm", matches = "true")
class TaskOrchestrationBaselineTests {

    @Autowired
    private TaskPlanningFacade taskPlanningFacade;

    @Autowired
    private TaskOrchestrationService taskOrchestrationService;

    @Autowired
    private AiIntentClassifier aiIntentClassifier;

    @Autowired
    private ArticlesMapper articlesMapper;

    @Autowired
    private AiAgentRunMapper runMapper;

    @Autowired
    private AiAgentStepMapper stepMapper;

    /** mock 掉真 ES vectorStore（与评测门同规矩：基线不依赖本地 ES） */
    @MockBean
    private VectorStore vectorStore;

    private static final Path OUTPUT = Path.of("target", "task-orchestration-baseline.txt");

    private static final Long FIXTURE_AUTHOR_ID = 100L;
    private static final String FIXTURE_TITLE = "复合基线夹具：缓存工程化长文";

    /** 只跑复合句：纯确定性句没有子 run，证明不了父子口径 */
    private static final List<String> MESSAGES = List.of(
            "点赞这篇文章，再分析一下它写得怎么样",
            // P2.2：多步样本——观察累积到 3+ 步时，结构化上下文的收益才显出来
            "结合我的写作偏好，分析一下这篇文章写得怎么样，再找找站内有没有相关的文章"
    );

    @Test
    void collectCompositeTokenBaseline() throws Exception {
        Articles article = createLongArticleFixture();

        PageContextDTO pageContext = new PageContextDTO();
        pageContext.setPageType("article-detail");
        pageContext.setArticleId(String.valueOf(article.getId()));
        pageContext.setAuthorId(String.valueOf(article.getAuthorId()));

        StringBuilder report = new StringBuilder();
        report.append("===== 复合任务 Token 基线（2026-09-30，第四步·阶段 6）=====\n");
        report.append("夹具：articleId=").append(article.getId())
                .append(" 正文=").append(article.getContent().length()).append(" 字符")
                .append(" | userId=").append(article.getAuthorId())
                .append(" | 真实 LLM | 数据从 DB 读回\n");

        for (String message : MESSAGES) {
            collectOne(message, pageContext, report);
        }

        Files.createDirectories(OUTPUT.getParent());
        Files.writeString(OUTPUT, report.toString(), StandardCharsets.UTF_8);
        System.out.println(report);
    }

    private void collectOne(String message, PageContextDTO pageContext, StringBuilder report) {
        report.append("\n--- 样本：").append(message).append('\n');

        // ① 路由（真实分类器消耗）
        TokenUsageAccumulator routeUsage = new TokenUsageAccumulator();
        AiIntent intent = aiIntentClassifier.classify(message, pageContext, FIXTURE_AUTHOR_ID, routeUsage);
        report.append("  分类器：intent=").append(intent == null ? "null" : intent.getIntent())
                .append(" | requestKind=").append(intent == null ? "-" : intent.getRequestKind())
                .append(" | taskGoals=").append(intent == null ? "-" : intent.getTaskGoals())
                .append('\n');
        report.append("  路由 token=").append(routeUsage.getTotalTokens()).append('\n');

        // ② 计划与路由决策
        TaskRoute route = taskPlanningFacade.decide(message, intent, FIXTURE_AUTHOR_ID);
        report.append("  路由决策=").append(route.route());
        if (route.plan() != null) {
            report.append(" | steps=").append(route.plan().steps().stream()
                    .map(s -> s.type().name()).toList());
        }
        report.append('\n');

        if (!route.isOrchestrate()) {
            report.append("  未走 ORCHESTRATE（").append(route.route())
                    .append("），本样本不计入复合基线\n");
            return;
        }

        // ③ 执行（外层 run + 可能的子 run）
        OrchestrationOutcome outcome = taskOrchestrationService.run(
                FIXTURE_AUTHOR_ID, null, route.plan(), pageContext, null, null);
        Long rootRunId = outcome.agentRunId();
        report.append("  外层 run=").append(rootRunId).append('\n');

        AiAgentRun root = runMapper.selectById(rootRunId);
        long rootLocal = tokensOf(root);
        List<AiAgentRun> children = runMapper.selectList(new LambdaQueryWrapper<AiAgentRun>()
                .eq(AiAgentRun::getParentRunId, rootRunId));
        long childLocal = children.stream().mapToLong(this::tokensOf).sum();

        report.append("  外层 run local token=").append(rootLocal).append('\n');
        for (AiAgentRun child : children) {
            report.append("  子 run=").append(child.getId())
                    .append(" | goal=").append(shorten(child.getGoal()))
                    .append(" | local token=").append(tokensOf(child)).append('\n');
        }

        long subtree = taskOrchestrationService.subtreeTokens(rootRunId);
        report.append("  子树聚合（subtreeTokens）=").append(subtree).append('\n');
        report.append("  message 口径 = 路由 + 子树 = ").append(routeUsage.getTotalTokens() + subtree)
                .append('\n');

        // ④ 步骤留痕（外层 TASK_STEP + 子 run 的内部步骤）
        report.append("  外层步骤：\n");
        for (AiAgentStep step : stepMapper.selectList(new LambdaQueryWrapper<AiAgentStep>()
                .eq(AiAgentStep::getAgentRunId, rootRunId)
                .orderByAsc(AiAgentStep::getStepNo))) {
            report.append("    #").append(step.getStepNo())
                    .append(' ').append(step.getActionType())
                    .append(" state=").append(step.getStatus())
                    .append(" out=").append(shorten(step.getOutputJson()))
                    .append('\n');
        }
        for (AiAgentRun child : children) {
            report.append("  子 run ").append(child.getId()).append(" 内部步骤：\n");
            for (AiAgentStep step : stepMapper.selectList(new LambdaQueryWrapper<AiAgentStep>()
                    .eq(AiAgentStep::getAgentRunId, child.getId())
                    .orderByAsc(AiAgentStep::getStepNo))) {
                report.append("    #").append(step.getStepNo())
                        .append(' ').append(step.getActionType())
                        .append(" in=").append(step.getInputTokens())
                        .append(" out=").append(step.getOutputTokens())
                        .append(' ').append(step.getStatus())
                        .append('\n');
            }
        }

        // ⑤ 口径校验：所有 run 的 local 求和 == 子树聚合（不重复、不漏算）
        assertThat(rootLocal + childLocal)
                .as("所有 run 的 local Token 求和必须等于子树聚合值——否则口径有重复或漏算")
                .isEqualTo(subtree);
    }

    private long tokensOf(AiAgentRun run) {
        return run == null || run.getTotalTokens() == null ? 0L : run.getTotalTokens();
    }

    private String shorten(String text) {
        if (text == null) {
            return "-";
        }
        String oneLine = text.replaceAll("\\s+", " ");
        return oneLine.length() <= 160 ? oneLine : oneLine.substring(0, 160) + "…";
    }

    /** 与 AgentTokenBaselineTests 同一套夹具思路：10 节 × 20 段，贴近真实长文体量 */
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
        article.setSummary("复合基线夹具（非业务数据）");
        article.setContent(content.toString());
        article.setStatus(1);
        article.setViewCount(0);
        article.setPublishedAt(LocalDateTime.now());
        article.setCreatedAt(LocalDateTime.now());
        article.setUpdatedAt(LocalDateTime.now());
        articlesMapper.insert(article);
        return article;
    }

    /** 夹具必须清理：H2 内存库在同一 JVM 内被多个测试类共享（DB_CLOSE_DELAY=-1） */
    @AfterEach
    void cleanFixture() {
        articlesMapper.delete(new LambdaQueryWrapper<Articles>()
                .eq(Articles::getTitle, FIXTURE_TITLE));
    }
}
