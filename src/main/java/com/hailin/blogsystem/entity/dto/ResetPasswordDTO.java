package com.hailin.blogsystem.entity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 用邮件链接里的 token 设置新密码。
 *
 * <p><b>刻意不加密码强度下限</b>：与 {@link RegisterDTO} 保持同一条规则——
 * 校验边界只对齐数据库列宽，不引入新的产品约束。注册时允许的密码，
 * 重置时也必须允许，否则会出现"注册能用、重置被拒"的割裂。
 */
@Data
public class ResetPasswordDTO {

    @NotBlank(message = "重置链接无效")
    @Size(max = 128, message = "重置凭证不合法")
    private String token;

    @NotBlank(message = "新密码不能为空")
    @Size(min = 8, max = 64, message = "密码至少 8 个字符，最长 64 个字符")
    private String newPassword;
}
