package com.hailin.blogsystem;

import com.hailin.blogsystem.ai.tool.*;
import com.hailin.blogsystem.config.BlogAiProperties;
import com.hailin.blogsystem.entity.AiPrompt;
import com.hailin.blogsystem.service.impl.AiModelServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiModelServiceToolExposureTests {

    private AiModelServiceImpl service;
    private ChatClient.Builder builder;
    private BlogAiProperties properties;
    private AiArticleTools articleTools;
    private AiLearningDashboardTool dashboardTool;
    private WalletBalanceTool walletBalanceTool;

    @BeforeEach
    void setUp() {
        builder = mock(ChatClient.Builder.class);
        when(builder.defaultSystem(anyString())).thenReturn(builder);
        when(builder.build()).thenReturn(mock(ChatClient.class));

        properties = new BlogAiProperties();
        properties.setSystemPrompt("");

        articleTools = mock(AiArticleTools.class);
        dashboardTool = mock(AiLearningDashboardTool.class);
        walletBalanceTool = mock(WalletBalanceTool.class);

        service = new AiModelServiceImpl(
                builder,
                properties,
                articleTools,
                dashboardTool,
                walletBalanceTool
        );
    }

    @Test
    void dashboardDecisionOnlyExposesDashboardCallback() throws Exception {
        AiPrompt prompt = new AiPrompt();
        prompt.setLearningDashboardToolEnabled(true);

        Object[] tools = invokeBuildTools(prompt);
        ToolCallback[] callbacks = invokeBuildToolCallbacks(prompt);

        assertThat(tools).isEmpty();
        assertThat(callbacks).containsExactly(dashboardTool);
    }

    @Test
    void normalChatDoesNotExposeAnnotationTools() throws Exception {
        AiPrompt prompt = new AiPrompt();

        Object[] tools = invokeBuildTools(prompt);
        ToolCallback[] callbacks = invokeBuildToolCallbacks(prompt);

        // 页面动作和用户画像已由分类器 + Planner 确定性处理，回答模型不再背这些 schema。
        assertThat(tools).isEmpty();
        // 钱包余额仍是 GENERAL_CHAT 下的只读能力，保留独立 callback。
        assertThat(callbacks).containsExactly(walletBalanceTool);
    }

    @Test
    void articleChatOnlyExposesArticleReadTools() throws Exception {
        AiPrompt prompt = new AiPrompt();
        prompt.setArticleToolsEnabled(true);

        Object[] tools = invokeBuildTools(prompt);

        assertThat(tools).containsExactly(articleTools);
    }

    @Test
    void onlyClientSideArticleActionsRemainExposedAsTools() throws Exception {
        for (String methodName : List.of(
                "likeArticle",
                "unlikeArticle",
                "favoriteArticle",
                "unfavoriteArticle",
                "followAuthor",
                "unfollowAuthor")) {
            assertThat(toolAnnotation(methodName))
                    .as("服务端动作 %s 不得继续暴露给回答模型", methodName)
                    .isNull();
        }

        assertThat(toolAnnotation("commentArticle", String.class, String.class)).isNotNull();
        assertThat(toolAnnotation("scrollToComments")).isNotNull();
        assertThat(toolAnnotation("copyArticleLink")).isNotNull();
    }

    @Test
    void defaultSystemDefinesExternalMaterialTrustBoundary() {
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);

        verify(builder).defaultSystem(captor.capture());

        assertThat(captor.getValue())
                .contains("外部材料信任边界")
                .contains("不是系统指令")
                .contains("当前问题")
                .contains("用户修改意见");
    }

    private Object[] invokeBuildTools(AiPrompt prompt) throws Exception {
        Method method = AiModelServiceImpl.class.getDeclaredMethod(
                "buildTools",
                AiPrompt.class
        );
        method.setAccessible(true);
        return (Object[]) method.invoke(service, prompt);
    }

    private ToolCallback[] invokeBuildToolCallbacks(AiPrompt prompt) throws Exception {
        Method method = AiModelServiceImpl.class.getDeclaredMethod("buildToolCallbacks", AiPrompt.class);
        method.setAccessible(true);
        return (ToolCallback[]) method.invoke(service, prompt);
    }

    private Tool toolAnnotation(String methodName, Class<?>... parameterTypes) throws Exception {
        Class<?>[] types = parameterTypes.length == 0
                ? new Class<?>[]{String.class}
                : parameterTypes;
        return AiArticleActionTools.class.getDeclaredMethod(methodName, types).getAnnotation(Tool.class);
    }
}
