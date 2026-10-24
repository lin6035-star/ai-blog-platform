package com.hailin.blogsystem;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hailin.blogsystem.entity.AiMessages;
import com.hailin.blogsystem.entity.AiSessions;
import com.hailin.blogsystem.entity.AiWorkflowRun;
import com.hailin.blogsystem.entity.AiWorkflowStepLog;
import com.hailin.blogsystem.entity.dto.AiCreateSessionDTO;
import com.hailin.blogsystem.entity.dto.AiWorkflowStatus;
import com.hailin.blogsystem.entity.vo.AiSessionVO;
import com.hailin.blogsystem.mapper.AiMessageMapper;
import com.hailin.blogsystem.mapper.AiSessionMapper;
import com.hailin.blogsystem.mapper.AiWorkflowRunMapper;
import com.hailin.blogsystem.mapper.AiWorkflowStepLogMapper;
import com.hailin.blogsystem.service.AiSessionService;
import com.hailin.blogsystem.service.impl.AiMessageServiceImpl;
import com.hailin.blogsystem.utils.UserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class AiSessionServiceTests {

    private static final Long TEST_USER_ID = 102L;

    @Autowired
    private AiSessionService aiSessionService;

    @Autowired
    private AiSessionMapper aiSessionMapper;

    @Autowired
    private AiMessageMapper aiMessageMapper;

    @Autowired
    private AiWorkflowRunMapper aiWorkflowRunMapper;

    @Autowired
    private AiWorkflowStepLogMapper aiWorkflowStepLogMapper;

    @Autowired
    private AiMessageServiceImpl aiMessageService;

    @AfterEach
    void clearUserContext() {
        // 清理本测试创建的会话及其关联数据（测试的 H2 库跨用例共享）
        aiSessionMapper.selectList(new LambdaQueryWrapper<AiSessions>()
                        .eq(AiSessions::getUserId, TEST_USER_ID))
                .forEach(session -> {
                    aiMessageMapper.delete(new LambdaQueryWrapper<AiMessages>()
                            .eq(AiMessages::getSessionId, session.getId()));
                    aiWorkflowRunMapper.delete(new LambdaQueryWrapper<AiWorkflowRun>()
                            .eq(AiWorkflowRun::getConversationId, session.getId()));
                    aiSessionMapper.deleteById(session.getId());
                });
        UserContext.clear();
    }

    private AiSessions createSession() {
        AiSessions session = new AiSessions();
        session.setUserId(TEST_USER_ID);
        session.setTitle("Workflow 会话");
        session.setCreatedAt(LocalDateTime.now());
        session.setUpdatedAt(LocalDateTime.now());
        aiSessionMapper.insert(session);
        return session;
    }

    private AiWorkflowRun createRun(AiSessions session) {
        AiWorkflowRun run = new AiWorkflowRun();
        run.setUserId(TEST_USER_ID);
        run.setConversationId(session.getId());
        run.setWorkflowType("CREATE_ARTICLE");
        run.setWorkflowVersion("1.0");
        run.setStatus(AiWorkflowStatus.COMPLETED.name());
        run.setCurrentStep("FILL_ARTICLE");
        run.setContextJson("{}");
        run.setCreatedAt(LocalDateTime.now());
        run.setUpdatedAt(LocalDateTime.now());
        aiWorkflowRunMapper.insert(run);
        return run;
    }

    private void createStepLog(AiWorkflowRun run) {
        AiWorkflowStepLog stepLog = new AiWorkflowStepLog();
        stepLog.setWorkflowRunId(run.getId());
        stepLog.setLogType("STEP");
        stepLog.setStep("GENERATE_OUTLINE");
        stepLog.setStepOrder(4);
        stepLog.setStatus("SUCCESS");
        stepLog.setRetryCount(0);
        stepLog.setStartedAt(LocalDateTime.now());
        stepLog.setCreatedAt(LocalDateTime.now());
        aiWorkflowStepLogMapper.insert(stepLog);
    }

    private void createMessage(AiSessions session, AiWorkflowRun run) {
        AiMessages message = new AiMessages();
        message.setSessionId(session.getId());
        message.setWorkflowRunId(run.getId().toString());
        message.setRole("assistant");
        message.setContent("已创建文章创作 Workflow");
        message.setCreatedAt(LocalDateTime.now());
        aiMessageMapper.insert(message);
    }

    @Test
    void createsSessionForCurrentUserWithDefaultTitle() {
        UserContext.set(101L);
        AiCreateSessionDTO dto = new AiCreateSessionDTO();

        AiSessionVO session = aiSessionService.createSession(dto);

        assertThat(session.getId()).isNotBlank();
        assertThat(session.getTitle()).isEqualTo("新对话");
        assertThat(session.getCreatedAt()).isNotNull();
        assertThat(session.getUpdatedAt()).isNotNull();
    }

    @Test
    void deleteSessionRemovesMessagesWorkflowRunsAndStepLogs() {
        UserContext.set(TEST_USER_ID);

        AiSessions session = createSession();
        AiWorkflowRun run = createRun(session);
        createStepLog(run);
        createMessage(session, run);

        aiSessionService.deleteSession(session.getId().toString());

        assertThat(aiMessageMapper.selectList(new LambdaQueryWrapper<AiMessages>()
                .eq(AiMessages::getSessionId, session.getId()))).isEmpty();
        assertThat(aiWorkflowRunMapper.selectList(new LambdaQueryWrapper<AiWorkflowRun>()
                .eq(AiWorkflowRun::getConversationId, session.getId()))).isEmpty();
        assertThat(aiWorkflowStepLogMapper.selectList(new LambdaQueryWrapper<AiWorkflowStepLog>()
                .eq(AiWorkflowStepLog::getWorkflowRunId, run.getId()))).isEmpty();
        assertThat(aiSessionMapper.selectById(session.getId())).isNull();
    }

    @Test
    void deleteSessionAlsoRemovesActiveWorkflowRunWithoutMessageReference() {
        UserContext.set(TEST_USER_ID);

        AiSessions session = createSession();
        AiWorkflowRun run = createRun(session);
        createStepLog(run);
        // 消息不引用 run，但 session.activeWorkflowRunId 仍指向它（例如进行中被打断的会话）
        session.setActiveWorkflowRunId(run.getId());
        aiSessionMapper.updateById(session);

        aiSessionService.deleteSession(session.getId().toString());

        assertThat(aiWorkflowRunMapper.selectList(new LambdaQueryWrapper<AiWorkflowRun>()
                .eq(AiWorkflowRun::getConversationId, session.getId()))).isEmpty();
        assertThat(aiWorkflowStepLogMapper.selectList(new LambdaQueryWrapper<AiWorkflowStepLog>()
                .eq(AiWorkflowStepLog::getWorkflowRunId, run.getId()))).isEmpty();
        assertThat(aiSessionMapper.selectById(session.getId())).isNull();
    }

    @Test
    void aiMessageServiceClearsActiveWorkflowBindingFromDatabase() {
        AiSessions session = createSession();
        session.setActiveWorkflowRunId(999001L);
        aiSessionMapper.updateById(session);

        ReflectionTestUtils.invokeMethod(
                aiMessageService,
                "clearSessionActiveWorkflow",
                session
        );

        AiSessions savedSession = aiSessionMapper.selectById(session.getId());
        assertThat(savedSession.getActiveWorkflowRunId()).isNull();
        assertThat(session.getActiveWorkflowRunId()).isNull();
    }

    /** 消息数为 0 的会话 → 清锚分支直接命中（本用例不建任何消息）。 */
    @Test
    void clearAnchorClearsAllThreeAnchorGroupsWhenSessionHasNoMessage() {
        // 2026-10-18：学习计划锚此前漏清——清空对话后说"继续改那个计划"，
        // 系统仍会从锚里捞出已删除对话里定位过的计划（V3.8 修过文章锚、V3.12 补过结论锚，学习域是漏网的）
        AiSessions session = createSession();
        session.setLastArticleId(12L);
        session.setLastArticleTitle("会话里的 A");
        session.setLastArticleSource("AGENT_RUN");
        session.setLastArticleUpdatedAt(LocalDateTime.now());
        session.setLastConclusionArticleId(12L);
        session.setLastConclusionText("建议补充缓存击穿原理");
        session.setLastConclusionSourceRunId(1L);
        session.setLastConclusionSourceType("ARTICLE_AGENT_FINAL_ANSWER");
        session.setLastConclusionUpdatedAt(LocalDateTime.now());
        session.setLastLearningPlanId(7L);
        session.setLastLearningPlanTitle("C++ 系统学习计划");
        session.setLastLearningPlanSource("CLASSIFIER");
        session.setLastLearningPlanUpdatedAt(LocalDateTime.now());
        aiSessionMapper.updateById(session);

        ReflectionTestUtils.invokeMethod(
                aiMessageService, "clearArticleAnchorIfSessionEmpty", session.getId());

        AiSessions saved = aiSessionMapper.selectById(session.getId());
        assertThat(saved.getLastArticleId()).isNull();
        assertThat(saved.getLastArticleTitle()).isNull();
        assertThat(saved.getLastArticleSource()).isNull();
        assertThat(saved.getLastArticleUpdatedAt()).isNull();
        assertThat(saved.getLastConclusionArticleId()).isNull();
        assertThat(saved.getLastConclusionText()).isNull();
        assertThat(saved.getLastConclusionSourceRunId()).isNull();
        assertThat(saved.getLastConclusionSourceType()).isNull();
        assertThat(saved.getLastConclusionUpdatedAt()).isNull();
        assertThat(saved.getLastLearningPlanId()).isNull();
        assertThat(saved.getLastLearningPlanTitle()).isNull();
        assertThat(saved.getLastLearningPlanSource()).isNull();
        assertThat(saved.getLastLearningPlanUpdatedAt()).isNull();
    }

    @Test
    void clearAnchorKeepsAnchorsWhileMessagesRemain() {
        // 负向：还有消息就不清——"清空对话 = 上下文归零"只对真的空了成立
        AiSessions session = createSession();
        session.setLastLearningPlanId(7L);
        session.setLastArticleId(12L);
        aiSessionMapper.updateById(session);
        createPlainMessage(session);

        ReflectionTestUtils.invokeMethod(
                aiMessageService, "clearArticleAnchorIfSessionEmpty", session.getId());

        AiSessions saved = aiSessionMapper.selectById(session.getId());
        assertThat(saved.getLastLearningPlanId()).isEqualTo(7L);
        assertThat(saved.getLastArticleId()).isEqualTo(12L);
    }

    private void createPlainMessage(AiSessions session) {
        AiMessages message = new AiMessages();
        message.setSessionId(session.getId());
        message.setRole("user");
        message.setContent("测试消息");
        message.setCreatedAt(LocalDateTime.now());
        aiMessageMapper.insert(message);
    }
}
