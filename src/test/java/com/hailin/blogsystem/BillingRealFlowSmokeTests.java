package com.hailin.blogsystem;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hailin.blogsystem.entity.AiBillingOrder;
import com.hailin.blogsystem.entity.AiChatEventType;
import com.hailin.blogsystem.entity.AiSessions;
import com.hailin.blogsystem.entity.dto.AiChatDTO;
import com.hailin.blogsystem.entity.vo.AiChatEventVO;
import com.hailin.blogsystem.entity.vo.AiWorkflowRunVO;
import com.hailin.blogsystem.mapper.AiBillingOrderMapper;
import com.hailin.blogsystem.mapper.AiSessionMapper;
import com.hailin.blogsystem.service.AiMessageService;
import com.hailin.blogsystem.service.WalletService;
import com.hailin.blogsystem.utils.UserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 计费补全的**真流程探针**：真 key、真分类器、真 Workflow、真 LLM。
 *
 * <p>为什么必须有它：{@code WorkflowActionBillingTests} 与 {@code AiRequestBillingSupportTests}
 * 都是离线门——它们 mock 掉了整条执行链，能证明「计费逻辑对」，证明不了「真链路上真的接上了」。
 * 这一例验证 B2：一句「帮我写一篇…」在真实分发链上确实开出了 {@code WORKFLOW_ACTION} 计费单，
 * 并且按真实 token 结算（不是全额退）。
 *
 * <p>⚠️ <b>真花 LLM 额度、耗时数十秒</b>，所以名字里不带 Tests 之外的标记、也不进日常回归，
 * 按需单跑：{@code .\mvnw.cmd -Dtest=BillingRealFlowSmokeTests test}
 */
@SpringBootTest
class BillingRealFlowSmokeTests {

    private static final String MESSAGE = "帮我写一篇关于 Redis 缓存穿透的文章";

    @Autowired
    private AiMessageService aiMessageService;

    @Autowired
    private WalletService walletService;

    @Autowired
    private AiBillingOrderMapper orderMapper;

    @Autowired
    private AiSessionMapper sessionMapper;

    private final Long userId =
            9_870_000_000_000L + Math.abs(System.nanoTime() % 1_000_000_000L);

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void workflowStartOnRealChainChargesByRealTokens() {
        UserContext.set(userId);
        recharge(50_000);

        AiSessions session = new AiSessions();
        session.setUserId(userId);
        session.setTitle("billing real flow");
        session.setCreatedAt(LocalDateTime.now());
        session.setUpdatedAt(LocalDateTime.now());
        sessionMapper.insert(session);

        AiChatDTO request = new AiChatDTO();
        request.setSessionId(String.valueOf(session.getId()));
        request.setMessage(MESSAGE);

        List<AiChatEventVO> events = aiMessageService.streamChat(request)
                .collectList()
                .block(Duration.ofMinutes(5));

        assertThat(events).as("真流程必须产出一串事件（超时或异常都会是 null）").isNotNull();

        AiChatEventVO stop = events.stream()
                .filter(e -> AiChatEventType.STOP.getValue().equals(e.getEventType()))
                .reduce((first, second) -> second)
                .orElseThrow(() -> new AssertionError("真流程没有 STOP 事件，事件类型：" +
                        events.stream().map(AiChatEventVO::getEventType).toList()));

        Object eventData = stop.getEventData();
        Object workflowValue = eventData instanceof Map<?, ?> data ? data.get("workflow") : null;
        assertThat(workflowValue)
                .as("这句请求应当落到 CREATE_ARTICLE Workflow（否则说明分类/路由变了，本探针要重新设计）")
                .isInstanceOf(AiWorkflowRunVO.class);

        String workflowRunId = ((AiWorkflowRunVO) workflowValue).getId();
        assertThat(workflowRunId).isNotBlank();

        List<AiBillingOrder> orders = awaitNonReservedOrder();

        assertThat(orders)
                .as("真链路上必须为这次 Workflow 启动开出一张计费单（补全前这里是空的）")
                .hasSize(1);

        AiBillingOrder order = orders.get(0);
        assertThat(order.getBizId()).startsWith("WF:START:");
        assertThat(order.getReservedCredit()).isEqualTo(10_000L);
        assertThat(order.getResourceId()).isEqualTo(workflowRunId);
        assertThat(order.getStatus())
                .as("LLM 真跑过就一定有 token，必须走结算而不是零消耗释放")
                .isEqualTo("SETTLED");
        assertThat(order.getActualCredit())
                .as("净额应当是按真实 token 折算出来的正数")
                .isPositive();
        assertThat(order.getActualCredit()).isLessThanOrEqualTo(order.getReservedCredit());

        long balance = walletService.getWallet(userId).getBalance();
        assertThat(balance)
                .as("余额 = 充值 - 真实消耗")
                .isEqualTo(50_000L - order.getActualCredit());
    }

    /**
     * 事件必须**陆续**到达，不能攒到流结束一次性吐出。
     *
     * <p>为什么要专门测这个：前端"这一步要做什么"的实时卡完全依赖 WORKFLOW_STEP 在步骤发生时
     * 就被推出去。一旦事件被缓冲到流末，用户就只能等到最后和最终回答一起看到——界面看上去
     * 像"没有过程"。而这在普通功能测试里**完全测不出来**：最终的事件序列一模一样，
     * 只有"到达时刻"不同。
     */
    @Test
    void workflowStepEventsArriveWhileWorkIsStillRunning() {
        UserContext.set(userId);
        recharge(50_000);

        AiSessions session = new AiSessions();
        session.setUserId(userId);
        session.setTitle("event timeline probe");
        session.setCreatedAt(LocalDateTime.now());
        session.setUpdatedAt(LocalDateTime.now());
        sessionMapper.insert(session);

        AiChatDTO request = new AiChatDTO();
        request.setSessionId(String.valueOf(session.getId()));
        request.setMessage(MESSAGE);

        // eventType 是枚举编码（Integer）不是字符串——记时刻要结构化，别拼字符串再解析
        record Tick(long atMs, Integer type) {
        }

        List<Tick> timeline = new ArrayList<>();
        long startedAt = System.currentTimeMillis();

        aiMessageService.streamChat(request)
                .doOnNext(event -> timeline.add(
                        new Tick(System.currentTimeMillis() - startedAt, event.getEventType())))
                .blockLast(Duration.ofMinutes(5));

        timeline.forEach(tick -> System.out.println("[TIMELINE] " + tick.atMs() + "ms type=" + tick.type()));

        long totalMs = System.currentTimeMillis() - startedAt;
        int stepType = AiChatEventType.WORKFLOW_STEP.getValue();
        long firstStepMs = timeline.stream()
                .filter(tick -> tick.type() != null && tick.type() == stepType)
                .mapToLong(Tick::atMs)
                .min()
                .orElseThrow(() -> new AssertionError(
                        "整个过程一条 WORKFLOW_STEP 都没有，事件序列：" + timeline));

        assertThat(firstStepMs)
                .as("首个步骤事件必须比整个流程结束早得多（实测首个 step 在几百毫秒内到达，"
                        + "总时长 %dms）。若这里几乎等于总时长，说明事件被攒到流末才发", totalMs)
                .isLessThan(totalMs - 1000);
    }

    private void recharge(long amount) {
        String tag = "REAL-FLOW-" + System.nanoTime();
        walletService.increase(userId, amount, "RECHARGE", "RECHARGE_ORDER",
                tag, "RECHARGE:" + tag, null);
    }

    /**
     * 轮询等结算落库。
     *
     * <p>为什么不能流一结束就查：Workflow 启动路径的结算发生在准入 worker 的 {@code finally} 里，
     * 而 {@code complete} 事件在它之前发出——所以「流结束」比「账单定下来」早几十毫秒。
     * 这是真实时序（前端若在 STOP 瞬间刷余额会看到预扣中），不是缺陷；测试必须等这一刻。
     */
    private List<AiBillingOrder> awaitNonReservedOrder() {
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            List<AiBillingOrder> orders = orderMapper.selectList(
                    new LambdaQueryWrapper<AiBillingOrder>()
                            .eq(AiBillingOrder::getUserId, userId)
                            .eq(AiBillingOrder::getBizType, "WORKFLOW_ACTION"));
            if (orders.size() == 1 && !"RESERVED".equals(orders.get(0).getStatus())) {
                return orders;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("等待结算被打断", e);
            }
        }
        throw new AssertionError("等了 20s 计费单仍不是终态，说明结算没有发生");
    }
}
