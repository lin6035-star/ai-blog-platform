package com.hailin.blogsystem.ai.agent;

import java.util.Map;

/**
 * 写动作确认结果。
 *
 * <p><b>为什么从 {@code String} 扩成这个结构</b>：AI 发起的充值现在只做到「创建订单 + 发起支付」，
 * 支付动作要交给用户——前端需要 {@code orderNo / payToken} 才能打开支付窗口。
 * 只返回一句 message 的话，前端没有任何办法继续。
 *
 * <p>{@code result} 就是执行器的 auditPayload（同时已落 {@code context_json.writeActionResult} 留痕）。
 * 其他域（学习 / 文章）的写动作没有前端后续动作，忽略它即可。
 *
 * <p>⚠️ 不要把服务端密钥之类的东西放进 result——它会原样返回浏览器。
 */
public record AgentWriteActionConfirmView(
        String message,
        String actionType,
        Map<String, Object> result
) {
}
