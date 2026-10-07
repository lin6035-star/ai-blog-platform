package com.hailin.blogsystem.ai;

import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/** Uses monotonic time for AI stage latency measurements. */
public final class AiStageTimer {

    private final LongSupplier nanoTime;
    private final long startedAt;

    private AiStageTimer(LongSupplier nanoTime) {
        this.nanoTime = nanoTime;
        this.startedAt = nanoTime.getAsLong();
    }

    public static AiStageTimer start() {
        return new AiStageTimer(System::nanoTime);
    }

    static AiStageTimer start(LongSupplier nanoTime) {
        return new AiStageTimer(nanoTime);
    }

    public long elapsedMillis() {
        long elapsedNanos = nanoTime.getAsLong() - startedAt;
        return Math.max(0L, TimeUnit.NANOSECONDS.toMillis(elapsedNanos));
    }
}
