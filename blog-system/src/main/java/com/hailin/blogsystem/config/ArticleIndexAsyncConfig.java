package com.hailin.blogsystem.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.support.ContextPropagatingTaskDecorator;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * outbox relay 的发送线程池。
 *
 * <p><b>单线程</b>：同一批 claim 出来的行必须按 id 顺序发送，才能让 MQ 侧的顺序尽量贴近 DB 写入顺序
 * （消费端还会回查 DB，所以顺序只影响收敛速度，不影响正确性）。
 * 发送是纯网络动作（毫秒级），单线程足够；embedding/ES 在消费端做，不占这个线程。</p>
 *
 * <p>有界队列 + AbortPolicy：队列满时拒绝，由 relay 把抢到的行退回去下一轮再来，
 * 而不是用 CallerRuns 把调度线程（pool.size=1）拖住。</p>
 */
@Configuration
public class ArticleIndexAsyncConfig {

    public static final String OUTBOX_EXECUTOR = "articleIndexOutboxExecutor";

    @Bean(OUTBOX_EXECUTOR)
    public ThreadPoolTaskExecutor articleIndexOutboxExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("index-outbox-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setTaskDecorator(new ContextPropagatingTaskDecorator());
        executor.initialize();
        return executor;
    }
}
