package com.hailin.blogsystem.controller;

import com.hailin.blogsystem.constants.BlogConstants;
import com.hailin.blogsystem.service.MailService;
import com.hailin.blogsystem.utils.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * SMTP 配置自检：往指定邮箱发一封测试信。
 *
 * <p><b>为什么需要它</b>：「配好了但发不出去」是邮件功能最典型的坑，而报错往往模棱两可
 * （认证失败 / 连接超时 / 被当成垃圾邮件，表现都是"没收到"）。有了它，配完配置先打一发，
 * 就能把「配置问题」和「业务问题」分开。
 *
 * <p><b>⚠️ 只在非 prod 环境注册</b>（{@code @Profile("!prod")}）：它是**匿名接口**，
 * 谁都能调、还能指定收件人——放到公网就是一台免费的邮件轰炸机。
 * 生产环境要验证邮件，走真实的「忘记密码」流程即可（那有验证码 + 双维度限流）。
 */
@RestController
@RequestMapping("/api/dev/mail")
@Profile("!prod")
@RequiredArgsConstructor
public class MailCheckController {

    private final MailService mailService;

    @PostMapping("/check")
    public Result<String> check(@RequestParam String to) {
        if (!mailService.isConfigured()) {
            return Result.error(BlogConstants.ErrorCode.BAD_REQUEST,
                    "邮件未配置：检查 MAIL_USERNAME / MAIL_PASSWORD（QQ 邮箱填的是授权码不是密码）");
        }

        // 故意用一个显眼的假 token：这封信只是用来验证 SMTP 通不通，
        // 拿到它去调 /reset 必然失败（Redis 里没有这个 key），不会误改任何人的密码
        mailService.sendPasswordResetEmail(to, "【这是一封测试邮件，链接不可用】");

        return Result.success("已投递（异步发送），请查收 " + to + "；"
                + "没收到先看后端日志的 [MAIL] 行");
    }
}
