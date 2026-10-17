package com.hailin.blogsystem.entity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class AiChatDTO {
    /** 会话 ID（雪花字符串）；游客不传 */
    @Size(max = 32, message = "会话标识不合法")
    private String sessionId;

    /**
     * 用户消息。
     *
     * <p>20000 字符上限是**成本护栏**不是产品限制：这条文本会直接进 prompt，
     * 不设上限时一个超长请求就能让单次 LLM 调用成本失控（2026-10-17 上线审计补）。
     *
     * <p>{@code @NotBlank} 把「消息为空」从业务层 IllegalArgumentException 提前到参数校验，
     * 响应格式保持项目约定（HTTP 200 + 40001），前端无感。
     */
    @NotBlank(message = "消息内容不能为空")
    @Size(max = 20000, message = "消息过长（最多 20000 字符）")
    private String message;

    private PageContextDTO pageContext;
}
