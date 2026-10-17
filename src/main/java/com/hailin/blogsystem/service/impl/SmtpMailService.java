package com.hailin.blogsystem.service.impl;

import com.hailin.blogsystem.service.MailService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * SMTP 发信实现。
 *
 * <p><b>用纯文本而不是 HTML</b>：重置邮件里唯一的变量是那个链接，而链接是自己生成的；
 * 换成 HTML 就得考虑转义、模板、外链图片被拦等等——为了好看引入一堆失败面不值得。
 *
 * <p><b>配置缺失时不在这里抛异常</b>：{@code isConfigured()} 只做判定，由调用方决定
 * 怎么处理（接口层给用户一句能看懂的话、启动时打一条 WARN）。构造函数里抛会让
 * 整个应用起不来，而"暂时不测邮件"是本地开发的正常状态。
 */
@Slf4j
@Service
public class SmtpMailService implements MailService {

    private final JavaMailSender mailSender;
    private final String from;
    private final boolean configured;

    public SmtpMailService(JavaMailSender mailSender,
                           @Value("${spring.mail.username:}") String username,
                           @Value("${spring.mail.password:}") String password) {
        this.mailSender = mailSender;
        this.from = username;
        this.configured = StringUtils.hasText(username) && StringUtils.hasText(password);

        if (!configured) {
            // 只警告不失败：本地开发经常不配邮件，但**必须留痕**——
            // 否则"忘记密码点了没反应"会被当成功能 bug 查半天
            log.warn("[MAIL] 未配置 spring.mail.username/password，忘记密码功能将不可用。"
                    + "QQ 邮箱填的是授权码不是密码，见 application.yml 的 spring.mail 注释");
        } else {
            log.info("[MAIL] 发信已配置: from={}", from);
        }
    }

    @Override
    public boolean isConfigured() {
        return configured;
    }

    /**
     * {@inheritDoc}
     *
     * <p><b>异步在这里，而不是在调用方</b>：{@code @Async} 靠 Spring 代理生效，
     * **同一个类内部自调用不会走代理**——把它放在调用方（Service 里调自己的方法）
     * 会静默失效，而且没有任何报错，表现为"接口响应被 SMTP 拖慢了几百毫秒"。
     * 挂在被跨类调用的实现方法上就没有这个陷阱。
     *
     * <p>失败只记日志、不抛回给调用方：发信失败不该让"忘记密码"接口变成 500，
     * 那会把"邮件服务抖了"暴露成一个功能报错。
     */
    @Override
    @Async("passwordResetMailExecutor")
    public void sendPasswordResetEmail(String to, String resetLink) {
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            // From 必须是登录 SMTP 的那个地址：QQ 邮箱会拒绝伪造发件人
            message.setFrom(from);
            message.setTo(to);
            message.setSubject("重置你的海林Blog密码");
            message.setText(buildBody(resetLink));
            mailSender.send(message);
            log.info("[MAIL] 重置邮件已发出: to={}", mask(to));
        } catch (Exception e) {
            // 收件人已经收到「已发送」的响应了（这是防枚举的必要代价），
            // 所以这里只能靠日志留痕，让它可被检索
            log.error("[MAIL] 重置邮件发送失败: to={}", mask(to), e);
        }
    }

    private String buildBody(String resetLink) {        return """
                你（或有人用这个邮箱）请求重置海林Blog的登录密码。

                点下面的链接设置新密码（30 分钟内有效，只能用一次）：
                %s

                如果这不是你发起的，忽略这封邮件即可——你的密码不会被改动。

                --
                海林Blog
                """.formatted(resetLink);
    }

    /** 日志里不打印完整邮箱（PII，日志会长期保留） */
    private String mask(String email) {
        int at = email.indexOf('@');
        return at <= 0 ? "***" : email.charAt(0) + "***" + email.substring(at);
    }
}
