package com.hailin.blogsystem;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hailin.blogsystem.ai.workflow.CreateArticleWorkflowHandler;
import com.hailin.blogsystem.ai.workflow.LlmStreamCaller;
import com.hailin.blogsystem.ai.workflow.WorkflowContextSupport;
import com.hailin.blogsystem.ai.workflow.WorkflowKnowledgeSupport;
import com.hailin.blogsystem.ai.workflow.WorkflowQualitySupport;
import com.hailin.blogsystem.ai.workflow.WorkflowStatusSupport;
import com.hailin.blogsystem.ai.workflow.WorkflowStepRunner;
import com.hailin.blogsystem.ai.workflow.WorkflowTokenRecorder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class CreateArticleWorkflowHandlerTests {

    private CreateArticleWorkflowHandler handler;

    @BeforeEach
    void setUp() {
        handler = new CreateArticleWorkflowHandler(
                mock(WorkflowContextSupport.class),
                mock(WorkflowStatusSupport.class),
                mock(WorkflowStepRunner.class),
                new ObjectMapper(),
                mock(ChatClient.Builder.class),
                mock(WorkflowTokenRecorder.class),
                mock(LlmStreamCaller.class),
                mock(WorkflowKnowledgeSupport.class),
                mock(WorkflowQualitySupport.class)
        );
    }

    @Test
    void genericRequestWithNiNengBuNengMustAskForTopic() {
        assertThat(handler.isRequirementUnclear(
                "你能不能帮我写一篇文章",
                null
        )).isTrue();
    }

    @Test
    void genericRequestWithWoXiangRangNiMustAskForTopic() {
        assertThat(handler.isRequirementUnclear(
                "我想让你帮我写一篇文章",
                null
        )).isTrue();
    }

    @Test
    void genericRequestWithQuestionSuffixMustAskForTopic() {
        assertThat(handler.isRequirementUnclear(
                "请问可以帮我写文章吗",
                null
        )).isTrue();
    }

    @Test
    void requestWithConcreteTopicMustContinueWorkflow() {
        assertThat(handler.isRequirementUnclear(
                "帮我写一篇 Redis 缓存文章",
                "Redis 缓存"
        )).isFalse();
    }

    @Test
    void hallucinatedTopicMustBeRejected() {
        assertThat(handler.isRequirementUnclear(
                "帮我写一篇文章",
                "Vibe Coding 与 AI Agent"
        )).isTrue();
    }

    @Test
    void articleAloneIsNotAValidTopic() {
        assertThat(handler.isRequirementUnclear(
                "帮我写一篇文章",
                "文章"
        )).isTrue();
    }

    /*
     * 以下三条锁的是 2026-10-04 的兜底：topicEvidence 缺失时改用规则抽取。
     *
     * 背景：分类器有两条路——plus 会输出 topicEvidence（prompt 要求逐字摘录原话），
     * 而 Jev 是 decision 模型，只出各 goal 的概率、抽不出文本片段。
     * 没有兜底时，"帮我写一篇 Redis 缓存设计的文章"走 Jev 会被追问主题、
     * 走 plus 却直接开跑，同一句话两种行为。
     */

    @Test
    void missingTopicEvidenceFallsBackToRuleExtraction() {
        assertThat(handler.isRequirementUnclear(
                "帮我写一篇 Redis 缓存设计的文章",
                null
        )).isFalse();
    }

    @Test
    void blankTopicEvidenceIsTreatedAsMissing() {
        assertThat(handler.isRequirementUnclear(
                "帮我写一篇 Redis 缓存设计的文章",
                "   "
        )).isFalse();
    }

    /** 兜底绝不能把无主题的请求放行——上面三条 generic 用例已锁，这里再压一次空主题边界。 */
    @Test
    void fallbackStillAsksForTopicWhenNothingUsableRemains() {
        assertThat(handler.isRequirementUnclear("帮我写一篇文章", null)).isTrue();
        assertThat(handler.isRequirementUnclear("我想让你帮我写一篇文章", null)).isTrue();
        assertThat(handler.isRequirementUnclear("写篇文章", null)).isTrue();
    }
}
