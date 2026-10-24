package com.hailin.blogsystem;

import com.hailin.blogsystem.config.BlogAiProperties;
import com.hailin.blogsystem.config.MemoryExtractionAsyncConfig;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MemoryRecallExecutorTests {

    @Test
    void saturatedRecallPoolRejectsImmediatelyInsteadOfQueueingStaleWork() throws Exception {
        MemoryExtractionAsyncConfig config = new MemoryExtractionAsyncConfig(new BlogAiProperties());
        ThreadPoolTaskExecutor executor = (ThreadPoolTaskExecutor) config.memoryRecallExecutor();
        CountDownLatch started = new CountDownLatch(4);
        CountDownLatch release = new CountDownLatch(1);

        try {
            assertThat(executor.getThreadPoolExecutor().getQueue().remainingCapacity()).isZero();

            Runnable blockingRecall = () -> {
                started.countDown();
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            };
            for (int i = 0; i < 4; i++) {
                executor.execute(blockingRecall);
            }

            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> executor.execute(() -> {
            })).isInstanceOf(TaskRejectedException.class);
        } finally {
            release.countDown();
            executor.shutdown();
        }
    }
}
