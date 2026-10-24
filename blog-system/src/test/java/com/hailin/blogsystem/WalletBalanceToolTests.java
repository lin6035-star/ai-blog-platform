package com.hailin.blogsystem;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hailin.blogsystem.ai.tool.WalletBalanceTool;
import com.hailin.blogsystem.entity.AiBillingOrder;
import com.hailin.blogsystem.entity.UserWallet;
import com.hailin.blogsystem.entity.WalletTransaction;
import com.hailin.blogsystem.mapper.AiBillingOrderMapper;
import com.hailin.blogsystem.mapper.UserWalletMapper;
import com.hailin.blogsystem.mapper.WalletTransactionMapper;
import com.hailin.blogsystem.service.WalletService;
import com.hailin.blogsystem.utils.UserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 钱包余额查询工具（只读）。
 *
 * <p>这个工具最大的风险不是查错数，而是**让模型在普通聊天里许诺「已为你充值」**
 * ——那会让用户以为钱到了而实际没有。所以有一条断言专门盯工具描述里的免责句。
 *
 * <p>另一条重点：负余额的**两种含义不能混**。预占中的负余额不是欠费，
 * 工具要是把「预占中」讲成「你欠费了」，模型就会照着提示用户去充值。
 */
@SpringBootTest
class WalletBalanceToolTests {

    @Autowired
    private WalletBalanceTool walletBalanceTool;

    @Autowired
    private WalletService walletService;

    @Autowired
    private UserWalletMapper userWalletMapper;

    @Autowired
    private WalletTransactionMapper walletTransactionMapper;

    @Autowired
    private AiBillingOrderMapper aiBillingOrderMapper;

    private final long userId = 9_600_000_000_000L + System.nanoTime() % 1_000_000_000L;

    @AfterEach
    void tearDown() {
        UserContext.clear();
        userWalletMapper.delete(new LambdaQueryWrapper<UserWallet>()
                .eq(UserWallet::getUserId, userId));
        walletTransactionMapper.delete(new LambdaQueryWrapper<WalletTransaction>()
                .eq(WalletTransaction::getUserId, userId));
        aiBillingOrderMapper.delete(new LambdaQueryWrapper<AiBillingOrder>()
                .eq(AiBillingOrder::getUserId, userId));
    }

    @Test
    void reportsZeroForAUserWithoutWallet() {
        UserContext.set(userId);

        String result = walletBalanceTool.call("", null);

        assertThat(result).contains("0").contains("额度已用尽");
    }

    @Test
    void reportsFormattedBalance() {
        UserContext.set(userId);
        walletService.increase(userId, 12_345L, "RECHARGE", "RECHARGE_ORDER",
                "T-1", "TEST:" + userId, "测试充值");

        String result = walletBalanceTool.call("", null);

        assertThat(result).contains("12,345");
        assertThat(result).doesNotContain("额度已用尽");
    }

    /** 预占中的负余额**不是欠费**——工具必须说清楚，否则模型会照着提示用户去充值 */
    @Test
    void doesNotTreatInFlightReserveAsOwedBalance() {
        UserContext.set(userId);
        walletService.increase(userId, 100L, "RECHARGE", "RECHARGE_ORDER",
                "T-2", "TEST:" + userId, "测试充值");
        walletService.decrease(userId, 500L, "AI_RESERVE", "AI_BILLING",
                "T-2", "TEST-AI:" + userId, "预扣");

        AiBillingOrder order = new AiBillingOrder();
        order.setOrderNo("B-TEST-" + userId);
        order.setUserId(userId);
        order.setBizType("CHAT");
        order.setBizId("T-2");
        order.setStatus("RESERVED");
        order.setReservedCredit(500L);
        order.setVersion(0);
        order.setExpireAt(LocalDateTime.now().plusMinutes(15));
        aiBillingOrderMapper.insert(order);

        String result = walletBalanceTool.call("", null);

        assertThat(result).contains("-400");
        assertThat(result)
                .as("预占中不是欠费，不该提示充值")
                .contains("正在进行中")
                .doesNotContain("额度已用尽");
    }

    /** 防模型在普通聊天里许诺「已为你充值」——工具描述必须写明自己做不到 */
    @Test
    void toolDescriptionSaysItCannotRecharge() {
        String description = walletBalanceTool.getToolDefinition().description();

        assertThat(description).contains("只读").contains("不能充值");
        assertThat(walletBalanceTool.getToolDefinition().name()).isEqualTo("queryWalletBalance");
    }
}
