package com.hailin.blogsystem.service;

/**
 * 发信口。
 *
 * <p><b>为什么抽成接口</b>：测试里必须能把它整个挡掉——否则每跑一次测试就会真的
 * 往某个邮箱发一封重置邮件（本地用的是真 SMTP，见 application.yml 的 spring.mail）。
 * 集成测试一律 {@code @MockBean MailService}。
 */
public interface MailService {

    /**
     * 发「重置密码」邮件。
     *
     * <p><b>实现是异步的</b>（见 {@code SmtpMailService} 上的 {@code @Async}）：
     * SMTP 往返几百毫秒起，挂在用户请求上既拖慢响应，又会让"邮箱存在/不存在"
     * 因为耗时不同而被探测出来。
     *
     * @param to        收件人，已归一化
     * @param resetLink 带一次性 token 的完整链接
     */
    void sendPasswordResetEmail(String to, String resetLink);

    /**
     * 配置是否齐全（账号 + 授权码都填了）。
     *
     * <p>用于两处：启动自检打日志、接口层快速失败给出**能看懂的**提示——
     * 而不是抛一个 "Authentication failed" 让排查的人去猜是账号错还是授权码错。
     */
    boolean isConfigured();
}
