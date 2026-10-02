package com.hailin.blogsystem.ai.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hailin.blogsystem.entity.dto.PageContextDTO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 快照编解码的往返一致性（5b 阶段 2）。
 *
 * <p>这个测试为什么必须有：快照的**写入端**（创建 run）比**读取端**（跨请求恢复）早写好几周，
 * 少存一个字段的话代码照样跑，只是恢复出来的计划少一条判据——最难发现的那种错。
 * 所以这里断言的是"写进去什么、读出来还是什么"，而不是"能读出个对象"。
 */
class TaskPlanSnapshotCodecTests {

    private final TaskPlanSnapshotCodec codec = new TaskPlanSnapshotCodec(new ObjectMapper());

    @Test
    @DisplayName("往返一致：目标 / 步骤 / 依赖 / 未覆盖类型 / 锚点一个都不能少")
    void roundTripKeepsEverythingRecoveryNeeds() {
        TaskStep first = new TaskStep("s1", StepType.ARTICLE_WRITE,
                Map.of("action", "likeArticle"), StepDependency.none(), List.of("点赞这篇文章"));
        TaskStep second = new TaskStep("s2", StepType.WORKFLOW_HANDOFF,
                Map.of("workflowType", "OPTIMIZE_ARTICLE"),
                new StepDependency(List.of("s1"), List.of("s1"), OnFailure.SKIP),
                List.of("再帮我优化一下"));
        TaskPlan plan = new TaskPlan("点赞这篇文章，再帮我优化一下", List.of(first, second),
                List.of("顺便看看别人的文章"), List.of("ARTICLE_QUERY"));

        PageContextDTO page = new PageContextDTO();
        page.setPageType("article-detail");
        page.setArticleId("42");

        TaskPlanSnapshotCodec.Snapshot snapshot = codec.read(codec.write(plan, page));

        assertThat(snapshot).isNotNull();
        TaskPlan restored = snapshot.plan();
        assertThat(restored.goal()).isEqualTo(plan.goal());
        assertThat(restored.steps()).hasSize(2);
        assertThat(restored.steps().get(0).stepId()).isEqualTo("s1");
        assertThat(restored.steps().get(0).type()).isEqualTo(StepType.ARTICLE_WRITE);
        assertThat(restored.steps().get(0).input()).containsEntry("action", "likeArticle");
        assertThat(restored.steps().get(0).goalEvidence()).containsExactly("点赞这篇文章");
        // 依赖必须原样回来：恢复时要靠它判断"从哪继续、哪些该跳"
        assertThat(restored.steps().get(1).dependency().after()).containsExactly("s1");
        assertThat(restored.steps().get(1).dependency().requires()).containsExactly("s1");
        assertThat(restored.steps().get(1).dependency().onFailure()).isEqualTo(OnFailure.SKIP);
        // 未覆盖目标的**类型**是路由与"禁止谎报完成"的依据，丢了就等于少一条判据
        assertThat(restored.uncoveredGoalEvidence()).containsExactly("顺便看看别人的文章");
        assertThat(restored.uncoveredTypes()).containsExactly("ARTICLE_QUERY");
        assertThat(restored.hasUncoveredGoals()).isTrue();

        assertThat(snapshot.anchor().getPageType()).isEqualTo("article-detail");
        assertThat(snapshot.anchor().getArticleId()).isEqualTo("42");
    }

    @Test
    @DisplayName("坏输入一律放弃恢复，不猜")
    void brokenInputYieldsNull() {
        assertThat(codec.read(null)).isNull();
        assertThat(codec.read("  ")).isNull();
        assertThat(codec.read("不是 JSON")).isNull();
        assertThat(codec.read("{}")).as("缺 goal").isNull();
        // 未知步骤类型 → 整份放弃：半份计划执行起来比不执行更危险
        assertThat(codec.read("{\"plan\":{\"goal\":\"x\",\"steps\":[{\"stepId\":\"s1\",\"type\":\"NOT_A_TYPE\"}]}}"))
                .isNull();
    }

    @Test
    @DisplayName("缺可选项时按默认值读出来，不整体失败")
    void missingOptionalFieldsFallBackToDefaults() {
        TaskPlanSnapshotCodec.Snapshot snapshot = codec.read(
                "{\"plan\":{\"goal\":\"只点赞\",\"steps\":[{\"stepId\":\"s1\",\"type\":\"ARTICLE_WRITE\"}]}}");

        assertThat(snapshot).isNotNull();
        assertThat(snapshot.plan().steps().get(0).input()).isEmpty();
        assertThat(snapshot.plan().steps().get(0).dependency().onFailure())
                .as("未知 / 缺失的失败策略落回 CONTINUE，不猜'更安全'的那个")
                .isEqualTo(OnFailure.CONTINUE);
        assertThat(snapshot.plan().uncoveredTypes()).isEmpty();
        assertThat(snapshot.anchor().getArticleId()).isNull();
    }
}
