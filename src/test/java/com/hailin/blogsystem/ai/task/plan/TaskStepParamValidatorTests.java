package com.hailin.blogsystem.ai.task.plan;

import com.hailin.blogsystem.ai.task.StepType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 步骤参数二级校验测试（C0.1，2026-10-01）。
 *
 * <p>锁的是「模型候选参数不经过后端校验就进执行器」这个缺口——
 * 页面手测拿到的越界值是 {@code ARTICLE_WRITE(action=changeTitle)}，
 * 但它代表的是一整类问题，所以这里覆盖四类契约 + 越界值 + 空值边界 + 超长值。
 */
class TaskStepParamValidatorTests {

    @Test
    @DisplayName("ARTICLE_WRITE：只有文章交互白名单内的 action 通过")
    void articleWriteActionWhitelist() {
        assertThat(TaskStepParamValidator.validate(StepType.ARTICLE_WRITE, Map.of("action", "likeArticle"))).isNull();
        assertThat(TaskStepParamValidator.validate(StepType.ARTICLE_WRITE, Map.of("action", "unfollowAuthor"))).isNull();

        // 页面手测的真实越界值：改标题不是低风险交互动作，它走的是受控写链
        String err = TaskStepParamValidator.validate(StepType.ARTICLE_WRITE, Map.of("action", "changeTitle"));
        assertThat(err).as("越界值必须被拒").isNotNull();
        assertThat(err).as("原因含原值——但它只用于内部日志").contains("changeTitle");

        // 属于**另一个能力层**的动作同样不能被塞进来
        assertThat(TaskStepParamValidator.validate(StepType.ARTICLE_WRITE,
                Map.of("action", "UPDATE_ARTICLE_TITLE")))
                .as("受控写动作不属于低风险执行器")
                .isNotNull();
    }

    @Test
    @DisplayName("ARTICLE_WRITE：缺 action / 空白 / params 为 null 一律拒绝")
    void articleWriteRequiresAction() {
        assertThat(TaskStepParamValidator.validate(StepType.ARTICLE_WRITE, Map.of())).contains("缺少 action");
        assertThat(TaskStepParamValidator.validate(StepType.ARTICLE_WRITE, Map.of("action", "   "))).contains("缺少 action");
        assertThat(TaskStepParamValidator.validate(StepType.ARTICLE_WRITE, null)).contains("缺少 action");
    }

    @Test
    @DisplayName("ARTICLE_SEARCH：query 非空即可")
    void articleSearchRequiresQuery() {
        assertThat(TaskStepParamValidator.validate(StepType.ARTICLE_SEARCH, Map.of("query", "Redis"))).isNull();
        assertThat(TaskStepParamValidator.validate(StepType.ARTICLE_SEARCH, Map.of())).contains("缺少 query");
        assertThat(TaskStepParamValidator.validate(StepType.ARTICLE_SEARCH, Map.of("query", " "))).contains("缺少 query");
    }

    @Test
    @DisplayName("NAVIGATE：第一版只认执行器实际支持的 userProfile（不复用前端七类导航白名单）")
    void navigateOnlySupportsUserProfile() {
        assertThat(TaskStepParamValidator.validate(StepType.NAVIGATE, Map.of("target", "userProfile"))).isNull();
        assertThat(TaskStepParamValidator.validate(StepType.NAVIGATE, Map.of("target", "home")))
                .as("执行器不支持的导航目标要在规划期就拒掉，而不是执行期失败")
                .isNotNull();
        assertThat(TaskStepParamValidator.validate(StepType.NAVIGATE, Map.of())).contains("缺少 target");
    }

    @Test
    @DisplayName("只读 / 分析类无必填参数")
    void analysisTypesHaveNoRequiredParams() {
        assertThat(TaskStepParamValidator.validate(StepType.ARTICLE_ANALYSIS, Map.of())).isNull();
        assertThat(TaskStepParamValidator.validate(StepType.ARTICLE_ANALYSIS, null)).isNull();
        assertThat(TaskStepParamValidator.validate(null, Map.of())).contains("步骤类型为空");
    }

    @Test
    @DisplayName("WORKFLOW_HANDOFF：params 只能含白名单内的 workflowType（5a 协议，5b 起校验）")
    void workflowHandoffOnlyAllowsWhitelistedType() {
        assertThat(TaskStepParamValidator.validate(StepType.WORKFLOW_HANDOFF,
                Map.of("workflowType", "OPTIMIZE_ARTICLE"))).isNull();

        // 这里刻意比"未知键一律放过"更严：对 WORKFLOW_HANDOFF 来说，多余键
        // （articleId / topic / planRef）不是无害冗余，而是**模型夹带业务定位值**的信号；
        // 放行就等于把这些脏值变成执行输入（5a 冻结协议的原话：
        // "否则模型夹带 articleId/topic/planRef 时仍会被放进 LEGACY，等 5b 开始消费这些字段
        //   就会把今天的脏候选变成明天的执行输入"）。
        Map<String, Object> withExtra = new HashMap<>();
        withExtra.put("workflowType", "OPTIMIZE_ARTICLE");
        withExtra.put("articleId", "12");
        assertThat(TaskStepParamValidator.validate(StepType.WORKFLOW_HANDOFF, withExtra))
                .as("多余键必须拒——通用尺度的'未知键无害'不适用于协议字段")
                .contains("只能含 workflowType");

        // 第一版只开 OPTIMIZE_ARTICLE：其余四个 Workflow 在领域登记表里，但不在 5b 白名单里
        assertThat(TaskStepParamValidator.validate(StepType.WORKFLOW_HANDOFF,
                Map.of("workflowType", "CREATE_ARTICLE"))).contains("白名单");
        assertThat(TaskStepParamValidator.validate(StepType.WORKFLOW_HANDOFF, Map.of()))
                .contains("只能含 workflowType");
    }

    @Test
    @DisplayName("只校验执行器依赖的键：多余字段一律放过（避免校验比执行还严）")
    void extraFieldsAreTolerated() {
        Map<String, Object> params = new HashMap<>();
        params.put("action", "likeArticle");
        params.put("note", "模型多给的字段");
        params.put("articleId", "12");   // 定位由后端按 anchorMode 决议，不该在这里拦

        assertThat(TaskStepParamValidator.validate(StepType.ARTICLE_WRITE, params))
                .as("未知键不是越界值——拦它就是用误杀换安全感")
                .isNull();
    }

    @Test
    @DisplayName("超长 / 带换行的越界值不会污染日志原因")
    void oversizedValueIsFlattenedInReason() {
        String huge = "changeTitle\n" + "x".repeat(500);
        String err = TaskStepParamValidator.validate(StepType.ARTICLE_WRITE, Map.of("action", huge));

        assertThat(err).isNotNull();
        assertThat(err).as("换行必须被压平").doesNotContain("\n");
        assertThat(err.length()).as("原因本身也要短——它是要写进日志的").isLessThan(140);
    }
}
