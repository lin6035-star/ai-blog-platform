package com.hailin.blogsystem;

import com.hailin.blogsystem.config.BlogAiProperties;
import com.hailin.blogsystem.entity.AiPrompt;
import com.hailin.blogsystem.service.AiConversationMemoryService;
import com.hailin.blogsystem.service.AiConversationSummaryService;
import com.hailin.blogsystem.service.AiEpisodicMemoryService;
import com.hailin.blogsystem.service.AiUserMemoryService;
import com.hailin.blogsystem.service.impl.AiPromptServiceImpl;
import com.hailin.blogsystem.utils.UserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class AiPromptServiceMemoryRecallTests {

    @AfterEach
    void clearUserContext() {
        UserContext.clear();
    }

    @Test
    void rejectedMemoryRecallFailsOpenWithoutBreakingPromptConstruction() {
        AiPromptServiceImpl service = new AiPromptServiceImpl();
        BlogAiProperties properties = new BlogAiProperties();
        properties.getMemory().setEnabled(true);
        Executor rejectingExecutor = task -> {
            throw new RejectedExecutionException("recall pool saturated");
        };

        ReflectionTestUtils.setField(service, "blogAiProperties", properties);
        ReflectionTestUtils.setField(service, "aiConversationMemoryService",
                mock(AiConversationMemoryService.class));
        ReflectionTestUtils.setField(service, "aiConversationSummaryService",
                mock(AiConversationSummaryService.class));
        ReflectionTestUtils.setField(service, "aiUserMemoryService", mock(AiUserMemoryService.class));
        ReflectionTestUtils.setField(service, "aiEpisodicMemoryService",
                mock(AiEpisodicMemoryService.class));
        ReflectionTestUtils.setField(service, "memoryRecallExecutor", rejectingExecutor);
        UserContext.set(7L);

        AiPrompt prompt = service.buildPrompt("你好", null, null);

        assertThat(prompt.getUserMessage()).isEqualTo("你好");
        assertThat(prompt.getFinalPromptContext()).contains("## 当前问题", "你好");
    }
}
