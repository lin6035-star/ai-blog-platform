package com.hailin.blogsystem;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hailin.blogsystem.entity.dto.AiIntent;
import com.hailin.blogsystem.entity.dto.PageContextDTO;
import com.hailin.blogsystem.service.AiIntentClassifier;
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
import java.util.List;

/**
 * taskGoals 探针（第三刀 → 第四刀之间的临时诊断，**只观察不断言**）。
 *
 * <p>动机：{@code TaskPlanningFacadeTests} 全部是手构 {@code AiIntent}，在线分类器评测集的断言字段里
 * 也没有 {@code taskGoals} / {@code requestKind}——所以「模型到底有没有把第二个目标填进 taskGoals」
 * 在生产链路里从未被观测过。而它直接决定「点赞 + 分析」走 ORCHESTRATE（静默丢分析）
 * 还是 LEGACY（正确）。
 *
 * <p>写法刻意只打印：这是"先观察、再断言"的一步。观察结论稳定后再把断言落进
 * {@code classifier-evaluation-cases.json}，探针本身不承担回归职责。
 *
 * <p>结果写文件而不是只打 stdout——surefire 可能重定向测试输出。
 * 跑法：{@code mvnw test -Dtest=TaskGoalsProbeTests -DrunRealLlm=true}
 */
@Tag("eval")
@SpringBootTest
@EnabledIfSystemProperty(named = "runRealLlm", matches = "true")
class TaskGoalsProbeTests {

    @Autowired
    private AiIntentClassifier aiIntentClassifier;

    /** mock 掉真 ES vectorStore（与评测门同规矩：不依赖本地 ES） */
    @MockBean
    private VectorStore vectorStore;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Path OUTPUT = Path.of("target", "task-goals-probe.txt");

    /** 五句话覆盖：第二刀核心句 / 第一刀两个验收句 / 两个单目标对照 */
    private static final List<String> MESSAGES = List.of(
            "点赞这篇文章，再分析一下它写得怎么样",
            "点赞这篇文章，再跳转到该作者的个人主页",
            "点赞这篇文章，再帮我找找还有没有关于Redis的文章",
            "点赞这篇文章",
            "分析一下这篇文章"
    );

    @Test
    void dumpTaskGoalsForTaskRequests() throws Exception {
        StringBuilder report = new StringBuilder();
        report.append("===== taskGoals 探针（pageType=article-detail, articleId=12, userId=null）=====\n");

        for (String message : MESSAGES) {
            PageContextDTO pageContext = new PageContextDTO();
            pageContext.setPageType("article-detail");
            pageContext.setArticleId("12");

            AiIntent intent = aiIntentClassifier.classify(message, pageContext, null, null);

            report.append("\n--- ").append(message).append('\n');
            if (intent == null) {
                report.append("  <null intent>\n");
                continue;
            }
            report.append("  intent=").append(intent.getIntent())
                    .append(" | requestKind=").append(intent.getRequestKind())
                    .append(" | confidence=").append(intent.getConfidence()).append('\n');
            report.append("  requestedActions=").append(intent.getRequestedActions())
                    .append(" | analysisGoalEvidence=").append(intent.getAnalysisGoalEvidence()).append('\n');
            report.append("  taskGoals=").append(MAPPER.writeValueAsString(intent.getTaskGoals())).append('\n');
            report.append("  reason=").append(intent.getReason()).append('\n');
        }

        Files.createDirectories(OUTPUT.getParent());
        Files.writeString(OUTPUT, report.toString(), StandardCharsets.UTF_8);
        System.out.println(report);
    }
}
