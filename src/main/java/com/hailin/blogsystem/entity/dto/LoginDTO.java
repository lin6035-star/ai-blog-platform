package com.hailin.blogsystem.entity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 登录请求。
 *
 * <p>边界同 {@link RegisterDTO}：只做「非空 + 对齐列宽上限」，不引入新的下限——
 * 登录尤其不能加下限，否则**历史上用短用户名注册的账号会突然登不进来**。
 */
@Data
public class LoginDTO {

    @NotBlank(message = "用户名不能为空")
    @Size(max = 50, message = "用户名最长 50 个字符")
    private String username;

    @NotBlank(message = "密码不能为空")
    @Size(max = 64, message = "密码最长 64 个字符")
    private String password;

    /**
     * 验证码凭证：{@code POST /api/auth/captcha/check} 通过后拿到的一次性 id。
     *
     * <p>名字叫「凭证」而不是 captchaId，是为了把语义说清楚：它不是标识哪张验证码，
     * 而是**证明这次请求确实完成过一次验证**。服务端消费即销毁（getAndRemove），重放无效。
     *
     * <p>必填，且**不带默认值**：登录接口不是「可选加强验证码」，而是强制校验。
     */
    @NotBlank(message = "请先完成安全验证")
    private String captchaVerification;

    /**
     * 记住我：勾选给长有效期（30 天），不勾给短有效期（24 小时）。
     *
     * <p><b>null 一律按「不记住」处理</b>（判定用 {@code Boolean.TRUE.equals}）：
     * 老前端不传这个字段时，宁可让用户多登一次，也不要默默把有效期拉长 30 倍。
     *
     * <p>注意这里传的是<b>意愿</b>，不是「时长」——具体多少天由服务端配置决定
     * （{@code jwt.expire.long} / {@code jwt.expire.short}）。让客户端传时长等于
     * 把令牌策略交给调用方，改配置也要跟着改前端。
     */
    private Boolean rememberMe;
}
