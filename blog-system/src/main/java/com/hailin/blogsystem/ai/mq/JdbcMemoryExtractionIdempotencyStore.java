package com.hailin.blogsystem.ai.mq;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 基于 MySQL 唯一键的消息幂等记录。
 *
 * <p>抢占、业务处理和成功标记由消费者的同一事务包住；处理失败时整笔事务回滚，
 * RocketMQ 可以再次投递，而不是被一个提前写入的去重标记吞掉。</p>
 */
@Repository
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "blog.ai.memory.extraction", name = "use-mq", havingValue = "true")
public class JdbcMemoryExtractionIdempotencyStore implements MemoryExtractionIdempotencyStore {

    private final JdbcTemplate jdbcTemplate;

    @PostConstruct
    void ensureTable() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS ai_memory_extraction_events (
                    event_id VARCHAR(128) NOT NULL PRIMARY KEY,
                    event_type VARCHAR(32) NOT NULL,
                    status VARCHAR(32) NOT NULL,
                    created_at TIMESTAMP NOT NULL,
                    updated_at TIMESTAMP NOT NULL
                )
                """);
    }

    @Override
    public boolean tryStart(String eventId, String eventType) {
        try {
            jdbcTemplate.update("""
                    INSERT INTO ai_memory_extraction_events
                        (event_id, event_type, status, created_at, updated_at)
                    VALUES (?, ?, 'PROCESSING', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    """, eventId, eventType);
            return true;
        } catch (DuplicateKeyException duplicate) {
            return false;
        }
    }

    @Override
    public void markSucceeded(String eventId) {
        jdbcTemplate.update("""
                UPDATE ai_memory_extraction_events
                   SET status = 'SUCCEEDED', updated_at = CURRENT_TIMESTAMP
                 WHERE event_id = ?
                """, eventId);
    }
}
