package com.hailin.blogsystem.ai;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class AiStageTimerTests {

    @Test
    void elapsedMillisUsesMonotonicNanosAndNeverReturnsNegative() {
        AtomicLong nanos = new AtomicLong(1_000_000L);
        AiStageTimer timer = AiStageTimer.start(nanos::get);

        nanos.set(4_500_000L);
        assertThat(timer.elapsedMillis()).isEqualTo(3L);

        nanos.set(500_000L);
        assertThat(timer.elapsedMillis()).isZero();
    }
}
