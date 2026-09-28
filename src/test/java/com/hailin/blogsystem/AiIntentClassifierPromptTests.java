package com.hailin.blogsystem;

import com.hailin.blogsystem.service.impl.AiIntentClassifierImpl;
import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class AiIntentClassifierPromptTests {

    @Autowired
    private AiIntentClassifierImpl classifier;

    /** mock 掉真 ES vectorStore（prompt 文本测试只依赖分类器 bean，不依赖本地 ES 可用性） */
    @MockBean
    private VectorStore vectorStore;

    @Test
    void promptDefinesSemanticLearningWorkflowIntents() throws Exception {
        Method method = AiIntentClassifierImpl.class.getDeclaredMethod("buildSystemPrompt", boolean.class);
        method.setAccessible(true);
        String prompt = (String) method.invoke(classifier, false);

        assertThat(prompt).contains("LEARNING_PROGRESS");
        assertThat(prompt).contains("LEARNING_ASSIST");
        assertThat(prompt).contains("LEARNING_PLAN_QUERY");
        assertThat(prompt).contains("learningPlanRef");
        assertThat(prompt).contains("learningStageRef");
        assertThat(prompt).contains("confidence");
        assertThat(prompt).contains("CREATE_ARTICLE");
        assertThat(prompt).contains("OPTIMIZE_ARTICLE");
        assertThat(prompt).contains("ARTICLE_SEARCH");
        assertThat(prompt).contains("ARTICLE_DETAIL_QA");
        // V2.5：文章侧 Agent 意图 + 模糊优化诉求规则
        assertThat(prompt).contains("ARTICLE_AGENT");
        assertThat(prompt).contains("模糊的优化诉求");
    }

    @Test
    void promptDefinesFullDomainWorkflowRouting() throws Exception {
        Method method = AiIntentClassifierImpl.class
                .getDeclaredMethod("buildSystemPrompt", boolean.class);
        method.setAccessible(true);

        String prompt = (String) method.invoke(classifier, false);

        assertThat(prompt)
                .contains("CREATE_ARTICLE")
                .contains("OPTIMIZE_ARTICLE")
                .contains("LEARNING_PLAN")
                .contains("LEARNING_PROGRESS")
                .contains("LEARNING_ASSIST");

        assertThat(prompt)
                .contains("ARTICLE_SEARCH")
                .contains("ARTICLE_DETAIL_QA");

        assertThat(prompt)
                .contains("ARTICLE_SEARCH")
                .contains("suggestedAction=CHAT");
    }

    @Test
    void promptDefinesAgentSuggestionFields() throws Exception {
        Method method = AiIntentClassifierImpl.class.getDeclaredMethod("buildSystemPrompt", boolean.class);
        method.setAccessible(true);
        String prompt = (String) method.invoke(classifier, false);

        assertThat(prompt).contains("suggestedAction");
        assertThat(prompt).contains("suggestedWorkflowType");
        assertThat(prompt).contains("risk");
        assertThat(prompt).contains("reason");

        assertThat(prompt).contains("CHAT");
        assertThat(prompt).contains("TOOL");
        assertThat(prompt).contains("WORKFLOW");
        assertThat(prompt).contains("CTA");

        assertThat(prompt).contains("这些字段只是给后端 Planner 的建议");
    }

    @Test
    void promptRequiresAllArticleActionsInOrderedCandidateArray() throws Exception {
        Method method = AiIntentClassifierImpl.class.getDeclaredMethod("buildSystemPrompt", boolean.class);
        method.setAccessible(true);
        String prompt = (String) method.invoke(classifier, false);

        assertThat(prompt).contains("requestedActions");
        assertThat(prompt).contains("点赞并收藏");
        assertThat(prompt).contains("[\"likeArticle\",\"favoriteArticle\"]");
        assertThat(prompt).contains("不能只保留一个");
    }

    @Test
    void promptDefinesNeedsThinkingSemanticRule() throws Exception {
        Method method = AiIntentClassifierImpl.class.getDeclaredMethod("buildSystemPrompt", boolean.class);
        method.setAccessible(true);
        String prompt = (String) method.invoke(classifier, false);

        // V3 通用思考模式：needsThinking 只对 GENERAL_CHAT 生效、默认 false、语义判定非词表
        assertThat(prompt).contains("needsThinking");
        assertThat(prompt).contains("needsThinkingReason");
        assertThat(prompt).contains("只对 GENERAL_CHAT 有意义");
        assertThat(prompt).contains("默认 false");
        assertThat(prompt).contains("判定标准是语义，不是关键词");
        assertThat(prompt).contains("依赖用户记忆/历史状态");
        assertThat(prompt).contains("不影响路由结果");
    }

    /**
     * 钱包域（2026-09-21）：充值意图 + **反例**。
     *
     * <p>反例和意图本身一样重要——「我余额够1000吗」这种句子里带数字，
     * 分类器一旦把它判成充值，后端就会从原话里解出 1000 并弹出一张充值卡。
     * 卡片会把金额念出来（用户扫一眼就知道不对），但根本不该弹。
     */
    @Test
    void promptDefinesWalletAgentIntentWithQueryCounterExample() throws Exception {
        Method method = AiIntentClassifierImpl.class.getDeclaredMethod("buildSystemPrompt", boolean.class);
        method.setAccessible(true);
        String prompt = (String) method.invoke(classifier, false);

        assertThat(prompt).contains("WALLET_AGENT");
        assertThat(prompt).contains("反例");
        assertThat(prompt).contains("问余额");
        // 金额不归分类器管——它由后端从用户原话确定性解析（WalletAmountParser）
        assertThat(prompt).contains("金额不用你提取");
    }
}
