package com.hailin.blogsystem.entity.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 「忘记密码」申请。
 *
 * <p><b>为什么也要验证码</b>：这是**未登录**接口，不加验证码的话，脚本可以直接
 * 拿一份邮箱列表来刷——限流虽然能挡住量，但验证码把"每个请求都很便宜"这个前提
 * 直接破坏掉了，两者是互补的。
 *
 * <p>响应**不区分邮箱是否存在**（见 PasswordResetService.requestReset）。
 */
@Data
public class ForgotPasswordDTO {

    @NotBlank(message = "邮箱不能为空")
    @Email(message = "邮箱格式不正确")
    @Size(max = 255, message = "邮箱最长 255 个字符")
    private String email;

    /** 验证码凭证，语义同 {@link LoginDTO#getCaptchaVerification()}：一次性，消费即销毁 */
    @NotBlank(message = "请先完成安全验证")
    private String captchaVerification;
}
