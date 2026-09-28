package com.hailin.blogsystem;

import com.hailin.blogsystem.ai.tool.*;
import com.hailin.blogsystem.config.BlogAiProperties;
import com.hailin.blogsystem.entity.AiPrompt;
import com.hailin.blogsystem.service.AiIntentClassifier;
import com.hailin.blogsystem.service.impl.AiModelServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AiModelServiceToolExposureTests {

    private AiModelServiceImpl service;
    private ChatClient.Builder builder;
    private BlogAiProperties properties;
    private AiArticleTools articleTools;
    private AiNavigationTools navigationTools;
    private AiEditorTools editorTools;
    private AiArticleActionTools articleActionTools;
    private AiUserProfileTools userProfileTools;
    private AiLearningDashboardTool dashboardTool;
    private WalletBalanceTool walletBalanceTool;
    private AiNavigationToolsFactory navigationToolsFactory;
    private AiEditorToolFactory editorToolFactory;
    private AiArticleActionToolsFactory articleActionToolsFactory;

    @BeforeEach
    void setUp() {
        builder = mock(ChatClient.Builder.class);
        when(builder.defaultSystem(anyString())).thenReturn(builder);
        when(builder.build()).thenReturn(mock(ChatClient.class));

        properties = new BlogAiProperties();
        properties.setSystemPrompt("");

        articleTools = mock(AiArticleTools.class);

        navigationTools = mock(AiNavigationTools.class);
        editorTools = mock(AiEditorTools.class);
        articleActionTools = mock(AiArticleActionTools.class);

        navigationToolsFactory = mock(AiNavigationToolsFactory.class);
        editorToolFactory = mock(AiEditorToolFactory.class);
        articleActionToolsFactory = mock(AiArticleActionToolsFactory.class);

        when(navigationToolsFactory.create(anyString()))
                .thenReturn(navigationTools);

        when(editorToolFactory.create(anyString()))
                .thenReturn(editorTools);

        when(articleActionToolsFactory.create(anyString()))
                .thenReturn(articleActionTools);

        userProfileTools = mock(AiUserProfileTools.class);
        dashboardTool = mock(AiLearningDashboardTool.class);
        walletBalanceTool = mock(WalletBalanceTool.class);

        service = new AiModelServiceImpl(
                builder,
                properties,
                articleTools,
                navigationToolsFactory,
                editorToolFactory,
                articleActionToolsFactory,
                userProfileTools,
                mock(AiIntentClassifier.class),
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
    void normalChatDoesNotExposeLegacyLearningPlanTools() throws Exception {
        AiPrompt prompt = new AiPrompt();

        Object[] tools = invokeBuildTools(prompt);
        ToolCallback[] callbacks = invokeBuildToolCallbacks(prompt);

        // 钱包余额查询挂在默认分支上——它是只读的，和不带参数的普通聊天共用同一条出口
        assertThat(callbacks).containsExactly(walletBalanceTool);
        assertThat(tools).containsExactly(
                navigationTools,
                editorTools,
                articleActionTools,
                userProfileTools
        );
        assertThat(Arrays.stream(tools).map(Object::getClass).map(Class::getSimpleName))
                .doesNotContain("AiLearningPlanTools", "QueryLearningPlansTool");
    }

    @Test
    void articleChatDoesNotExposeLegacyLearningPlanTools() throws Exception {
        AiPrompt prompt = new AiPrompt();
        prompt.setArticleToolsEnabled(true);

        Object[] tools = invokeBuildTools(prompt);

        assertThat(tools).containsExactly(
                articleTools,
                navigationTools,
                editorTools,
                articleActionTools,
                userProfileTools
        );
        assertThat(Arrays.stream(tools).map(Object::getClass).map(Class::getSimpleName))
                .doesNotContain("AiLearningPlanTools", "QueryLearningPlansTool");
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

    private Object[] invokeBuildTools(AiPrompt prompt) throws Exception {
        Method method = AiModelServiceImpl.class.getDeclaredMethod(
                "buildTools",
                AiPrompt.class,
                AiNavigationTools.class,
                AiEditorTools.class,
                AiArticleActionTools.class
        );
        method.setAccessible(true);
        return (Object[]) method.invoke(service, prompt, navigationTools, editorTools, articleActionTools);
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
