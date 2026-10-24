package com.hailin.blogsystem.controller;

import com.hailin.blogsystem.constants.BlogConstants;
import com.hailin.blogsystem.entity.dto.ForgotPasswordDTO;
import com.hailin.blogsystem.entity.dto.ResetPasswordDTO;
import com.hailin.blogsystem.security.CaptchaVerifier;
import com.hailin.blogsystem.service.PasswordResetService;
import com.hailin.blogsystem.utils.ClientIpUtils;
import com.hailin.blogsystem.utils.Result;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 忘记密码。
 *
 * <p><b>免登录</b>：{@code /api/auth/**} 不在 {@code WebMvcConfig} 里 jwtInterceptor 的
 * 路径列表内——忘了密码的人本来就登录不进来，这是少数几个必须匿名可用的端点。
 */
@RestController
@RequestMapping("/api/auth/password")
@RequiredArgsConstructor
public class PasswordResetController {

    private final PasswordResetService passwordResetService;
    private final CaptchaVerifier captchaVerifier;

    /**
     * 申请重置（发信）。
     *
     * <p><b>响应恒定，但有一个例外</b>：邮箱存不存在、是不是 GitHub 账号、有没有被限流
     * ——这四种情况**必须返回同一个成功响应**（前端的提示文案也要固定成一句，
     * 不要写成"已发送到 xxx@xx"，那等于把"这个邮箱注册过"写在界面上）。
     *
     * <p><b>唯一的例外是"邮件服务不可用"</b>：那是**全局**故障，与任何用户无关，
     * 所以明确报错——否则界面一片绿、实际什么都没发生，配置错误只能靠翻日志发现。
     * 这个豁免不会削弱防枚举：它和"这个邮箱如何"没有任何关系。
     */
    @PostMapping("/forgot")
    public Result<Void> forgot(@Valid @RequestBody ForgotPasswordDTO dto, HttpServletRequest request) {
        captchaVerifier.verify(dto.getCaptchaVerification());

        PasswordResetService.RequestOutcome outcome =
                passwordResetService.requestReset(dto.getEmail(), ClientIpUtils.getClientIp(request));

        if (outcome == PasswordResetService.RequestOutcome.MAIL_UNAVAILABLE) {
            // ⚠️ 面向用户的文案**不能出现配置项名**（MAIL_USERNAME 之类是内部实现细节，
            // 用户看了只会困惑）。**该说的细节在日志里**——PasswordResetService 已经用
            // log.error 记了「邮件未配置 + 该配哪个变量」，那才是运维该看的地方。
            return Result.error(BlogConstants.ErrorCode.MAIL_UNAVAILABLE,
                    "邮件服务暂不可用，请稍后重试或联系管理员");
        }

        return Result.success();
    }

    /**
     * 凭邮件里的 token 设置新密码。
     *
     * <p>成功后会**作废该用户所有已签发的 token**（含他自己其它设备上的登录）——
     * 详见 {@code TokenVersionStore}。链接失效时返回
     * {@code PASSWORD_RESET_INVALID}，前端据此引导"重新申请一次"。
     */
    @PostMapping("/reset")
    public Result<Void> reset(@Valid @RequestBody ResetPasswordDTO dto) {
        passwordResetService.resetPassword(dto.getToken(), dto.getNewPassword());
        return Result.success();
    }
}
