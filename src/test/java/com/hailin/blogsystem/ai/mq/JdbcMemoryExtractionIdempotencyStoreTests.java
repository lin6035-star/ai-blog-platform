package com.hailin.blogsystem.ai.mq;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcMemoryExtractionIdempotencyStoreTests {

    private final JdbcMemoryExtractionIdempotencyStore store = createStore();

    @Test
    void sameEventCanOnlyBeStartedOnce() {
        assertThat(store.tryStart("memory:candidate:100", "CANDIDATE")).isTrue();
        assertThat(store.tryStart("memory:candidate:100", "CANDIDATE")).isFalse();
    }

    @Test
    void successfulEventCanStillNotBeStartedAgain() {
        assertThat(store.tryStart("memory:candidate:101", "CANDIDATE")).isTrue();
        store.markSucceeded("memory:candidate:101");

        assertThat(store.tryStart("memory:candidate:101", "CANDIDATE")).isFalse();
    }

    private static JdbcMemoryExtractionIdempotencyStore createStore() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:memory_idempotency;DB_CLOSE_DELAY=-1", "sa", "");
        JdbcMemoryExtractionIdempotencyStore store =
                new JdbcMemoryExtractionIdempotencyStore(new JdbcTemplate(dataSource));
        store.ensureTable();
        return store;
    }
}
