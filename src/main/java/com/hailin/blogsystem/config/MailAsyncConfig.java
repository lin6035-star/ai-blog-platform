package com.hailin.blogsystem.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * 忘记密码的发信线程池。
 *
 * <p><b>为什么要独立池</b>：发信是 SMTP 往返（几百毫秒起），失败方式还和业务无关
 * （对端限流、网络抖动）。共用默认执行器的话，"邮件服务慢"会变成"别的异步任务也排队"。
 *
 * <p><b>为什么只有 1 个工作线程</b>：忘记密码是低频操作，而且有
 * {@code PasswordResetRateLimiter} 兜着（每个邮箱 15 分钟最多 3 次）。
 * 单线程足够，还天然串行化——避免对 SMTP 服务端开并发连接
 * （QQ 邮箱对同一账号的并发连接是有限制的）。
 *
 * <p><b>队列有界（50）</b>：满了直接被 {@code AbortPolicy} 拒绝并记日志。
 * **刻意不用 CallerRunsPolicy** —— 那会把 Tomcat 请求线程堵在 SMTP 往返上，
 * "异步发信"就白做了，还会让响应耗时随邮件服务状态抖动（而响应耗时正是
 * 防枚举要盯住的东西）。
 */
@Configuration
public class MailAsyncConfig {

    @Bean("passwordResetMailExecutor")
    public Executor passwordResetMailExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("pwd-reset-mail-");
        // 默认就是 AbortPolicy，显式写出来是为了让"满队列怎么办"这个决定留在代码里
        executor.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        return executor;
    }
}
