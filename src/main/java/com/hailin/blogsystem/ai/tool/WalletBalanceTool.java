package com.hailin.blogsystem.ai.tool;

import com.hailin.blogsystem.entity.vo.WalletVO;
import com.hailin.blogsystem.service.WalletService;
import com.hailin.blogsystem.utils.UserContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.stereotype.Component;

/**
 * 钱包余额查询工具（手写 {@code ToolCallback} 而非 {@code @Tool} 注解，理由同
 * {@link QueryLearningPlansTool}：无参工具被 LLM 以空 arguments 调用时，
 * Spring AI 1.0.0 的 {@code MethodToolCallback} 会 Assert 失败炸掉整个聊天流）。
 *
 * <p><b>只读</b>——只调 {@code WalletService.getWallet}，不碰任何写路径。
 * 充值走的是另一条路（Agent 受控写动作 → 确认卡 → 用户点确认），
 * 这个工具既做不到、也不该做。工具描述里写死了这一点，是防模型在普通聊天里
 * 顺着用户的话许诺「已为你充值」——那会让用户以为钱到了而实际没有。
 */
@Slf4j
@Component
public class WalletBalanceTool implements ToolCallback {

    private static final String SCHEMA = """
            {"type":"object","properties":{},"required":[]}""";

    private final WalletService walletService;

    public WalletBalanceTool(WalletService walletService) {
        this.walletService = walletService;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder()
                .name("queryWalletBalance")
                .description("查询当前用户的额度（credit）余额。"
                        + "用户问「我余额还有多少」「额度够用吗」时调用。"
                        + "【只读】本工具只能查询，不能充值、不能修改任何数据——"
                        + "用户要求充值时要明确告诉他去「个人中心 → 我的额度」操作，不要声称已经充好了。")
                .inputSchema(SCHEMA)
                .build();
    }

    @Override
    public String call(String toolInput) {
        return call(toolInput, null);
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        Long userId = extractUserId(toolContext);
        if (userId == null) {
            return "当前未登录，无法查询钱包余额。";
        }
        log.info("AI工具调用：queryWalletBalance, userId={}", userId);

        WalletVO wallet = walletService.getWallet(userId);
        long balance = wallet.getBalance() == null ? 0L : wallet.getBalance();
        long pending = wallet.getPendingReserveCount() == null ? 0L : wallet.getPendingReserveCount();

        StringBuilder sb = new StringBuilder();
        sb.append("当前用户的额度余额：").append(String.format("%,d", balance)).append(" credit。\n");

        // 负余额有两种完全不同的含义，说清楚了模型才不会把「预占中」讲成「你欠费了」
        if (balance <= 0 && pending > 0) {
            sb.append("注意：余额为负是因为有 ").append(pending)
                    .append(" 笔 AI 调用正在进行中，结束后会按实际用量结算退回——")
                    .append("这不代表欠费，不要提示用户充值。\n");
        } else if (balance <= 0) {
            sb.append("注意：额度已用尽，需要充值后才能继续使用 AI 助手。\n");
        }

        sb.append("额度用于 AI 助手消费，按实际 token 用量扣减。"
                + "请基于以上真实数据回答，不要编造数字；"
                + "用户想充值的话，告诉他到「个人中心 → 我的额度」页面操作。");
        return sb.toString();
    }

    //userId 优先从 ToolContext 取（工具执行线程读不到 ThreadLocal），ThreadLocal 只作直接调用的兜底
    private Long extractUserId(ToolContext toolContext) {
        Object value = toolContext == null ? null : toolContext.getContext().get("userId");
        if (value instanceof Number number) {
            return number.longValue();
        }
        return UserContext.get();
    }
}
