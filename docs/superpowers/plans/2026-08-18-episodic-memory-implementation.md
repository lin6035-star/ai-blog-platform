# Episodic Memory V1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an Episodic Memory V1 backend loop that extracts important historical events from chat, stores them in MySQL, indexes them into the existing VectorStore with `source=episodic_memory`, retrieves them later, and injects them into normal chat and Workflow prompts.

**Architecture:** Keep Semantic/User Memory and Episodic Memory separated by service boundary, table, DTO, and VectorStore metadata. V1 reuses the existing ES index but all episodic retrieval must filter by `source=episodic_memory`, `userId`, and `projectKey`. Extraction runs asynchronously after chat completion and writes only high-confidence, high-importance event summaries, not raw message logs.

**Tech Stack:** Spring Boot, Java 17, MyBatis-Plus, Spring AI `ChatClient`, Spring AI `VectorStore`, Jackson, MySQL/H2 schema, JUnit 5.

---

## File Map

- Create: `blog-system/src/main/java/com/hailin/blogsystem/entity/AiEpisodicMemories.java`
- Create: `blog-system/src/main/java/com/hailin/blogsystem/mapper/AiEpisodicMemoryMapper.java`
- Create: `blog-system/src/main/java/com/hailin/blogsystem/entity/dto/EpisodicMemoryExtractResult.java`
- Create: `blog-system/src/main/java/com/hailin/blogsystem/entity/dto/EpisodicMemoryRagContext.java`
- Create: `blog-system/src/main/java/com/hailin/blogsystem/service/AiEpisodicMemoryService.java`
- Create: `blog-system/src/main/java/com/hailin/blogsystem/service/AiEpisodicMemoryExtractorService.java`
- Create: `blog-system/src/main/java/com/hailin/blogsystem/service/impl/AiEpisodicMemoryServiceImpl.java`
- Create: `blog-system/src/main/java/com/hailin/blogsystem/service/impl/AiEpisodicMemoryExtractorServiceImpl.java`
- Create: `blog-system/src/main/java/com/hailin/blogsystem/ai/memory/AiEpisodicMemoryIndexService.java`
- Create: `blog-system/src/main/java/com/hailin/blogsystem/ai/memory/AiEpisodicMemoryRetrieveService.java`
- Modify: `db/init.sql`
- Modify: `blog-system/src/test/resources/schema.sql`
- Modify: `blog-system/src/main/java/com/hailin/blogsystem/service/impl/AiMessageServiceImpl.java`
- Modify: `blog-system/src/main/java/com/hailin/blogsystem/service/impl/AiPromptServiceImpl.java`
- Modify: `blog-system/src/main/java/com/hailin/blogsystem/ai/workflow/WorkflowKnowledgeSupport.java`
- Test: `blog-system/src/test/java/com/hailin/blogsystem/AiEpisodicMemoryServiceTests.java`
- Test: `blog-system/src/test/java/com/hailin/blogsystem/AiEpisodicMemoryExtractorTests.java`

---

### Task 1: Add table, entity, mapper, and DTOs

**Files:**
- Modify: `db/init.sql`
- Modify: `blog-system/src/test/resources/schema.sql`
- Create: `blog-system/src/main/java/com/hailin/blogsystem/entity/AiEpisodicMemories.java`
- Create: `blog-system/src/main/java/com/hailin/blogsystem/mapper/AiEpisodicMemoryMapper.java`
- Create: `blog-system/src/main/java/com/hailin/blogsystem/entity/dto/EpisodicMemoryExtractResult.java`
- Create: `blog-system/src/main/java/com/hailin/blogsystem/entity/dto/EpisodicMemoryRagContext.java`

- [x] **Step 1: Add MySQL table to `db/init.sql`**

Put it after `ai_user_memory_candidates`:

```sql
-- ============================================
-- AI 情景记忆表
-- ============================================

CREATE TABLE IF NOT EXISTS ai_episodic_memories (
    id BIGINT PRIMARY KEY,

    user_id BIGINT NOT NULL COMMENT '用户ID',
    session_id BIGINT DEFAULT NULL COMMENT '来源会话ID',
    project_key VARCHAR(64) NOT NULL DEFAULT '01myBlog' COMMENT '项目隔离键',

    memory_type VARCHAR(32) NOT NULL COMMENT '情景记忆类型：DECISION / EVENT / MILESTONE / PLAN',
    title VARCHAR(120) NOT NULL COMMENT '短标题，方便列表和调试',
    content TEXT NOT NULL COMMENT '压缩后的历史事件描述，必须保留原因',

    importance TINYINT NOT NULL DEFAULT 6 COMMENT '重要性，1-10',
    confidence DECIMAL(3,2) NOT NULL DEFAULT 0.80 COMMENT '可信度，0-1',

    source_message_ids JSON DEFAULT NULL COMMENT '来源消息ID数组',
    content_hash CHAR(64) NOT NULL COMMENT '内容哈希，用于硬去重',

    occurred_at DATETIME NOT NULL COMMENT '事件发生时间，默认取窗口最后一条消息时间',
    last_retrieved_at DATETIME DEFAULT NULL COMMENT '最近一次被召回时间',
    retrieval_count INT NOT NULL DEFAULT 0 COMMENT '召回次数',

    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    UNIQUE KEY uk_episodic_user_project_hash (user_id, project_key, content_hash),
    KEY idx_episodic_user_project_time (user_id, project_key, occurred_at),
    KEY idx_episodic_user_type (user_id, memory_type),
    KEY idx_episodic_importance (user_id, project_key, importance)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 情景记忆表';
```

- [x] **Step 2: Add H2 test table to `blog-system/src/test/resources/schema.sql`**

Use `CLOB` and `VARCHAR` instead of MySQL `TEXT` / `JSON`:

```sql
CREATE TABLE IF NOT EXISTS ai_episodic_memories (
    id BIGINT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    session_id BIGINT,
    project_key VARCHAR(64) NOT NULL DEFAULT '01myBlog',
    memory_type VARCHAR(32) NOT NULL,
    title VARCHAR(120) NOT NULL,
    content CLOB NOT NULL,
    importance INT NOT NULL DEFAULT 6,
    confidence DECIMAL(3,2) NOT NULL DEFAULT 0.80,
    source_message_ids CLOB,
    content_hash VARCHAR(64) NOT NULL,
    occurred_at TIMESTAMP NOT NULL,
    last_retrieved_at TIMESTAMP,
    retrieval_count INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP,
    updated_at TIMESTAMP,
    CONSTRAINT uk_episodic_user_project_hash UNIQUE (user_id, project_key, content_hash)
);

CREATE INDEX IF NOT EXISTS idx_episodic_user_project_time
    ON ai_episodic_memories (user_id, project_key, occurred_at);
CREATE INDEX IF NOT EXISTS idx_episodic_user_type
    ON ai_episodic_memories (user_id, memory_type);
CREATE INDEX IF NOT EXISTS idx_episodic_importance
    ON ai_episodic_memories (user_id, project_key, importance);
```

- [x] **Step 3: Create entity**

Create `blog-system/src/main/java/com/hailin/blogsystem/entity/AiEpisodicMemories.java`:

```java
package com.hailin.blogsystem.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("ai_episodic_memories")
public class AiEpisodicMemories {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long userId;
    private Long sessionId;
    private String projectKey;

    private String memoryType;
    private String title;
    private String content;

    private Integer importance;
    private BigDecimal confidence;

    private String sourceMessageIds;
    private String contentHash;

    private LocalDateTime occurredAt;
    private LocalDateTime lastRetrievedAt;
    private Integer retrievalCount;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
```

- [x] **Step 4: Create mapper**

Create `blog-system/src/main/java/com/hailin/blogsystem/mapper/AiEpisodicMemoryMapper.java`:

```java
package com.hailin.blogsystem.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hailin.blogsystem.entity.AiEpisodicMemories;

public interface AiEpisodicMemoryMapper extends BaseMapper<AiEpisodicMemories> {
}
```

- [x] **Step 5: Create LLM extraction DTO**

Create `blog-system/src/main/java/com/hailin/blogsystem/entity/dto/EpisodicMemoryExtractResult.java`:

```java
package com.hailin.blogsystem.entity.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
public class EpisodicMemoryExtractResult {

    private Boolean shouldRemember;
    private String memoryType;
    private String title;
    private String content;
    private Integer importance;
    private BigDecimal confidence;
    private List<Long> sourceMessageIds;
    private LocalDateTime occurredAt;
}
```

- [x] **Step 6: Create retrieval context DTO**

Create `blog-system/src/main/java/com/hailin/blogsystem/entity/dto/EpisodicMemoryRagContext.java`:

```java
package com.hailin.blogsystem.entity.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record EpisodicMemoryRagContext(
        Long memoryId,
        Long userId,
        String projectKey,
        String memoryType,
        String title,
        String content,
        BigDecimal confidence,
        Integer importance,
        LocalDateTime occurredAt
) {
}
```

- [x] **Step 7: Compile**

Run:

```powershell
cd D:\AIAgent\01myBlog\blog-system
.\mvnw -DskipTests compile
```

Expected: compile passes.

---

### Task 2: Add episodic VectorStore indexing and retrieval

**Files:**
- Create: `blog-system/src/main/java/com/hailin/blogsystem/ai/memory/AiEpisodicMemoryIndexService.java`
- Create: `blog-system/src/main/java/com/hailin/blogsystem/ai/memory/AiEpisodicMemoryRetrieveService.java`
- Test: `blog-system/src/test/java/com/hailin/blogsystem/AiEpisodicMemoryServiceTests.java`

- [x] **Step 1: Create index service**

Create `blog-system/src/main/java/com/hailin/blogsystem/ai/memory/AiEpisodicMemoryIndexService.java`:

```java
package com.hailin.blogsystem.ai.memory;

import com.hailin.blogsystem.entity.AiEpisodicMemories;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class AiEpisodicMemoryIndexService {

    public static final String SOURCE_EPISODIC_MEMORY = "episodic_memory";

    private final VectorStore vectorStore;

    public void indexMemory(AiEpisodicMemories memory) {
        if (memory == null || memory.getId() == null || memory.getUserId() == null) {
            return;
        }

        deleteMemoryIndex(memory.getId());

        Document document = Document.builder()
                .id(buildDocumentId(memory.getId()))
                .text(memory.getContent())
                .metadata("source", SOURCE_EPISODIC_MEMORY)
                .metadata("memoryId", memory.getId())
                .metadata("userId", memory.getUserId())
                .metadata("sessionId", memory.getSessionId())
                .metadata("projectKey", memory.getProjectKey())
                .metadata("memoryType", memory.getMemoryType())
                .metadata("title", memory.getTitle())
                .metadata("importance", memory.getImportance())
                .metadata("confidence", memory.getConfidence())
                .metadata("contentHash", memory.getContentHash())
                .metadata("sourceMessageIds", memory.getSourceMessageIds())
                .metadata("occurredAt", memory.getOccurredAt() == null ? null : memory.getOccurredAt().toString())
                .build();

        vectorStore.add(List.of(document));
    }

    public void deleteMemoryIndex(Long memoryId) {
        if (memoryId == null) {
            return;
        }

        vectorStore.delete(new Filter.Expression(
                Filter.ExpressionType.AND,
                new Filter.Expression(
                        Filter.ExpressionType.EQ,
                        new Filter.Key("source"),
                        new Filter.Value(SOURCE_EPISODIC_MEMORY)
                ),
                new Filter.Expression(
                        Filter.ExpressionType.EQ,
                        new Filter.Key("memoryId"),
                        new Filter.Value(memoryId)
                )
        ));
    }

    private String buildDocumentId(Long memoryId) {
        return "episodic:" + memoryId;
    }
}
```

- [x] **Step 2: Create retrieval service**

Create `blog-system/src/main/java/com/hailin/blogsystem/ai/memory/AiEpisodicMemoryRetrieveService.java`:

```java
package com.hailin.blogsystem.ai.memory;

import com.hailin.blogsystem.entity.dto.EpisodicMemoryRagContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class AiEpisodicMemoryRetrieveService {

    private static final int PROMPT_TOP_K = 5;
    private static final int PROMPT_LIMIT = 3;
    private static final int DEDUPE_TOP_K = 3;
    private static final double PROMPT_SIMILARITY_THRESHOLD = 0.58;
    private static final double DEDUPE_SIMILARITY_THRESHOLD = 0.85;

    private final VectorStore vectorStore;

    public List<EpisodicMemoryRagContext> retrieveForPrompt(Long userId, String projectKey, String question) {
        if (userId == null || projectKey == null || projectKey.isBlank()
                || question == null || question.isBlank()) {
            return List.of();
        }

        try {
            List<Document> documents = vectorStore.similaritySearch(
                    SearchRequest.builder()
                            .query(question.trim())
                            .topK(PROMPT_TOP_K)
                            .similarityThreshold(PROMPT_SIMILARITY_THRESHOLD)
                            .filterExpression(episodicFilter(userId, projectKey))
                            .build()
            );

            if (documents == null || documents.isEmpty()) {
                return List.of();
            }

            List<EpisodicMemoryRagContext> contexts = documents.stream()
                    .filter(this::isEpisodicDocument)
                    .filter(document -> userId.equals(toLong(document.getMetadata().get("userId"))))
                    .filter(document -> projectKey.equals(toStringValue(document.getMetadata().get("projectKey"))))
                    .map(this::toContext)
                    .sorted(Comparator.comparingInt(this::importanceOrDefault).reversed())
                    .limit(PROMPT_LIMIT)
                    .toList();

            log.info("Episodic Memory 召回完成，userId={}, projectKey={}, count={}",
                    userId, projectKey, contexts.size());

            return contexts;
        } catch (Exception e) {
            log.warn("Episodic Memory 召回失败，userId={}, projectKey={}", userId, projectKey, e);
            return List.of();
        }
    }

    public List<EpisodicMemoryRagContext> retrieveForDedupe(Long userId, String projectKey, String content) {
        if (userId == null || projectKey == null || projectKey.isBlank()
                || content == null || content.isBlank()) {
            return List.of();
        }

        try {
            List<Document> documents = vectorStore.similaritySearch(
                    SearchRequest.builder()
                            .query(content.trim())
                            .topK(DEDUPE_TOP_K)
                            .similarityThreshold(DEDUPE_SIMILARITY_THRESHOLD)
                            .filterExpression(episodicFilter(userId, projectKey))
                            .build()
            );

            if (documents == null || documents.isEmpty()) {
                return List.of();
            }

            return documents.stream()
                    .filter(this::isEpisodicDocument)
                    .filter(document -> userId.equals(toLong(document.getMetadata().get("userId"))))
                    .filter(document -> projectKey.equals(toStringValue(document.getMetadata().get("projectKey"))))
                    .map(this::toContext)
                    .toList();
        } catch (Exception e) {
            log.warn("Episodic Memory 去重召回失败，userId={}, projectKey={}", userId, projectKey, e);
            return List.of();
        }
    }

    private Filter.Expression episodicFilter(Long userId, String projectKey) {
        return new Filter.Expression(
                Filter.ExpressionType.AND,
                new Filter.Expression(
                        Filter.ExpressionType.AND,
                        new Filter.Expression(
                                Filter.ExpressionType.EQ,
                                new Filter.Key("source"),
                                new Filter.Value(AiEpisodicMemoryIndexService.SOURCE_EPISODIC_MEMORY)
                        ),
                        new Filter.Expression(
                                Filter.ExpressionType.EQ,
                                new Filter.Key("userId"),
                                new Filter.Value(userId)
                        )
                ),
                new Filter.Expression(
                        Filter.ExpressionType.EQ,
                        new Filter.Key("projectKey"),
                        new Filter.Value(projectKey)
                )
        );
    }

    private boolean isEpisodicDocument(Document document) {
        return document != null
                && AiEpisodicMemoryIndexService.SOURCE_EPISODIC_MEMORY
                .equals(document.getMetadata().get("source"));
    }

    private EpisodicMemoryRagContext toContext(Document document) {
        Map<String, Object> metadata = document.getMetadata();

        return new EpisodicMemoryRagContext(
                toLong(metadata.get("memoryId")),
                toLong(metadata.get("userId")),
                toStringValue(metadata.get("projectKey")),
                toStringValue(metadata.get("memoryType")),
                toStringValue(metadata.get("title")),
                document.getText(),
                toBigDecimal(metadata.get("confidence")),
                toInteger(metadata.get("importance")),
                toLocalDateTime(metadata.get("occurredAt"))
        );
    }

    private int importanceOrDefault(EpisodicMemoryRagContext context) {
        return context.importance() == null ? 6 : context.importance();
    }

    private Long toLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            return Long.valueOf(text);
        }
        return null;
    }

    private Integer toInteger(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            return Integer.valueOf(text);
        }
        return null;
    }

    private BigDecimal toBigDecimal(Object value) {
        if (value instanceof BigDecimal number) {
            return number;
        }
        if (value instanceof Number number) {
            return BigDecimal.valueOf(number.doubleValue());
        }
        if (value instanceof String text && !text.isBlank()) {
            return new BigDecimal(text);
        }
        return new BigDecimal("0.80");
    }

    private LocalDateTime toLocalDateTime(Object value) {
        if (value instanceof LocalDateTime time) {
            return time;
        }
        if (value instanceof String text && !text.isBlank()) {
            return LocalDateTime.parse(text);
        }
        return null;
    }

    private String toStringValue(Object value) {
        return value == null ? "" : value.toString();
    }
}
```

- [x] **Step 3: Compile**

Run:

```powershell
cd D:\AIAgent\01myBlog\blog-system
.\mvnw -DskipTests compile
```

Expected: compile passes.

---

### Task 3: Add DB service and prompt builder

**Files:**
- Create: `blog-system/src/main/java/com/hailin/blogsystem/service/AiEpisodicMemoryService.java`
- Create: `blog-system/src/main/java/com/hailin/blogsystem/service/impl/AiEpisodicMemoryServiceImpl.java`
- Test: `blog-system/src/test/java/com/hailin/blogsystem/AiEpisodicMemoryServiceTests.java`

- [x] **Step 1: Create service interface**

Create `blog-system/src/main/java/com/hailin/blogsystem/service/AiEpisodicMemoryService.java`:

```java
package com.hailin.blogsystem.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hailin.blogsystem.entity.AiEpisodicMemories;
import com.hailin.blogsystem.entity.dto.EpisodicMemoryExtractResult;

public interface AiEpisodicMemoryService extends IService<AiEpisodicMemories> {

    String DEFAULT_PROJECT_KEY = "01myBlog";

    void saveExtractedMemory(Long userId, Long sessionId, EpisodicMemoryExtractResult result);

    String buildEpisodicPrompt(Long userId, String question);
}
```

- [x] **Step 2: Create service implementation**

Create `blog-system/src/main/java/com/hailin/blogsystem/service/impl/AiEpisodicMemoryServiceImpl.java`:

```java
package com.hailin.blogsystem.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hailin.blogsystem.ai.memory.AiEpisodicMemoryIndexService;
import com.hailin.blogsystem.ai.memory.AiEpisodicMemoryRetrieveService;
import com.hailin.blogsystem.entity.AiEpisodicMemories;
import com.hailin.blogsystem.entity.dto.EpisodicMemoryExtractResult;
import com.hailin.blogsystem.entity.dto.EpisodicMemoryRagContext;
import com.hailin.blogsystem.mapper.AiEpisodicMemoryMapper;
import com.hailin.blogsystem.service.AiEpisodicMemoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
@Slf4j
public class AiEpisodicMemoryServiceImpl
        extends ServiceImpl<AiEpisodicMemoryMapper, AiEpisodicMemories>
        implements AiEpisodicMemoryService {

    private static final BigDecimal MIN_CONFIDENCE = new BigDecimal("0.60");
    private static final int MIN_IMPORTANCE = 6;
    private static final int MAX_PROMPT_CONTENT_LENGTH = 200;

    private final AiEpisodicMemoryIndexService aiEpisodicMemoryIndexService;
    private final AiEpisodicMemoryRetrieveService aiEpisodicMemoryRetrieveService;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional
    public void saveExtractedMemory(Long userId, Long sessionId, EpisodicMemoryExtractResult result) {
        if (!isValidResult(userId, result)) {
            return;
        }

        String projectKey = DEFAULT_PROJECT_KEY;
        String content = result.getContent().trim();
        String contentHash = sha256(projectKey + "\n" + normalizeType(result.getMemoryType()) + "\n" + content);

        AiEpisodicMemories existing = lambdaQuery()
                .eq(AiEpisodicMemories::getUserId, userId)
                .eq(AiEpisodicMemories::getProjectKey, projectKey)
                .eq(AiEpisodicMemories::getContentHash, contentHash)
                .one();

        if (existing != null) {
            log.info("Episodic Memory 硬去重命中，userId={}, memoryId={}", userId, existing.getId());
            return;
        }

        List<EpisodicMemoryRagContext> similarMemories =
                aiEpisodicMemoryRetrieveService.retrieveForDedupe(userId, projectKey, content);
        if (!similarMemories.isEmpty()) {
            log.info("Episodic Memory 软去重命中，userId={}, count={}", userId, similarMemories.size());
            return;
        }

        LocalDateTime now = LocalDateTime.now();

        AiEpisodicMemories memory = new AiEpisodicMemories();
        memory.setUserId(userId);
        memory.setSessionId(sessionId);
        memory.setProjectKey(projectKey);
        memory.setMemoryType(normalizeType(result.getMemoryType()));
        memory.setTitle(limitText(result.getTitle().trim(), 120));
        memory.setContent(content);
        memory.setImportance(normalizeImportance(result.getImportance()));
        memory.setConfidence(normalizeConfidence(result.getConfidence()));
        memory.setSourceMessageIds(toJson(result.getSourceMessageIds()));
        memory.setContentHash(contentHash);
        memory.setOccurredAt(result.getOccurredAt() == null ? now : result.getOccurredAt());
        memory.setRetrievalCount(0);
        memory.setCreatedAt(now);
        memory.setUpdatedAt(now);

        save(memory);
        aiEpisodicMemoryIndexService.indexMemory(memory);

        log.info("Episodic Memory 已保存，userId={}, sessionId={}, memoryId={}, type={}",
                userId, sessionId, memory.getId(), memory.getMemoryType());
    }

    @Override
    public String buildEpisodicPrompt(Long userId, String question) {
        List<EpisodicMemoryRagContext> memories =
                aiEpisodicMemoryRetrieveService.retrieveForPrompt(userId, DEFAULT_PROJECT_KEY, question);

        if (memories.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("## 历史事件记忆\n");
        sb.append("以下是和当前问题相关的历史事件、决策、计划或里程碑。");
        sb.append("请只在相关时参考，用于解释过去为什么这样决定，不要机械复述。\n\n");

        for (EpisodicMemoryRagContext memory : memories) {
            sb.append("- [").append(memory.memoryType()).append("] ");
            if (memory.occurredAt() != null) {
                sb.append(memory.occurredAt().toLocalDate()).append("：");
            }
            sb.append(limitText(memory.content(), MAX_PROMPT_CONTENT_LENGTH)).append("\n");
        }

        return sb.toString().trim();
    }

    private boolean isValidResult(Long userId, EpisodicMemoryExtractResult result) {
        if (userId == null || result == null || !Boolean.TRUE.equals(result.getShouldRemember())) {
            return false;
        }
        if (!isAllowedType(result.getMemoryType())) {
            return false;
        }
        if (result.getTitle() == null || result.getTitle().isBlank()) {
            return false;
        }
        if (result.getContent() == null || result.getContent().isBlank()) {
            return false;
        }
        return normalizeConfidence(result.getConfidence()).compareTo(MIN_CONFIDENCE) >= 0
                && normalizeImportance(result.getImportance()) >= MIN_IMPORTANCE;
    }

    private boolean isAllowedType(String memoryType) {
        if (memoryType == null) {
            return false;
        }
        String type = memoryType.trim().toUpperCase(Locale.ROOT);
        return "DECISION".equals(type)
                || "EVENT".equals(type)
                || "MILESTONE".equals(type)
                || "PLAN".equals(type);
    }

    private String normalizeType(String memoryType) {
        return memoryType.trim().toUpperCase(Locale.ROOT);
    }

    private BigDecimal normalizeConfidence(BigDecimal confidence) {
        if (confidence == null) {
            return new BigDecimal("0.80");
        }
        if (confidence.compareTo(BigDecimal.ZERO) < 0) {
            return BigDecimal.ZERO;
        }
        if (confidence.compareTo(BigDecimal.ONE) > 0) {
            return BigDecimal.ONE;
        }
        return confidence;
    }

    private Integer normalizeImportance(Integer importance) {
        if (importance == null) {
            return 6;
        }
        if (importance < 1) {
            return 1;
        }
        if (importance > 10) {
            return 10;
        }
        return importance;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? List.of() : value);
        } catch (JsonProcessingException e) {
            return "[]";
        }
    }

    private String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("生成情景记忆哈希失败", e);
        }
    }

    private String limitText(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        if (text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength);
    }
}
```

- [x] **Step 3: Compile**

Run:

```powershell
cd D:\AIAgent\01myBlog\blog-system
.\mvnw -DskipTests compile
```

Expected: compile passes.

---

### Task 4: Add async episodic extractor

**Files:**
- Create: `blog-system/src/main/java/com/hailin/blogsystem/service/AiEpisodicMemoryExtractorService.java`
- Create: `blog-system/src/main/java/com/hailin/blogsystem/service/impl/AiEpisodicMemoryExtractorServiceImpl.java`
- Modify: `blog-system/src/main/java/com/hailin/blogsystem/service/impl/AiMessageServiceImpl.java`
- Test: `blog-system/src/test/java/com/hailin/blogsystem/AiEpisodicMemoryExtractorTests.java`

- [x] **Step 1: Create extractor interface**

Create `blog-system/src/main/java/com/hailin/blogsystem/service/AiEpisodicMemoryExtractorService.java`:

```java
package com.hailin.blogsystem.service;

public interface AiEpisodicMemoryExtractorService {

    void extractAfterChat(
            Long userId,
            Long sessionId,
            Long userMessageId,
            Long assistantMessageId,
            String userMessage,
            String assistantReply
    );
}
```

- [x] **Step 2: Create extractor implementation**

Create `blog-system/src/main/java/com/hailin/blogsystem/service/impl/AiEpisodicMemoryExtractorServiceImpl.java`:

```java
package com.hailin.blogsystem.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hailin.blogsystem.entity.AiMessages;
import com.hailin.blogsystem.entity.dto.EpisodicMemoryExtractResult;
import com.hailin.blogsystem.mapper.AiMessageMapper;
import com.hailin.blogsystem.service.AiEpisodicMemoryExtractorService;
import com.hailin.blogsystem.service.AiEpisodicMemoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class AiEpisodicMemoryExtractorServiceImpl implements AiEpisodicMemoryExtractorService {

    private static final int WINDOW_MESSAGE_COUNT = 20;
    private static final int WINDOW_TRIGGER_MOD = 10;

    private final AiMessageMapper aiMessageMapper;
    private final ChatClient.Builder chatClientBuilder;
    private final ObjectMapper objectMapper;
    private final AiEpisodicMemoryService aiEpisodicMemoryService;

    @Override
    @Async("memoryCandidateTaskExecutor")
    public void extractAfterChat(
            Long userId,
            Long sessionId,
            Long userMessageId,
            Long assistantMessageId,
            String userMessage,
            String assistantReply
    ) {
        if (userId == null || sessionId == null) {
            return;
        }

        boolean eventTriggered = shouldExtract(userMessage) || shouldExtract(assistantReply);
        long messageCount = countSessionMessages(sessionId);
        boolean windowTriggered = messageCount > 0 && messageCount % WINDOW_TRIGGER_MOD == 0;

        if (!eventTriggered && !windowTriggered) {
            return;
        }

        try {
            List<AiMessages> windowMessages = listRecentWindowMessages(sessionId);
            List<EpisodicMemoryExtractResult> results = extractByLlm(windowMessages);

            if (results.isEmpty()) {
                log.info("Episodic Memory 提取结果为空，userId={}, sessionId={}", userId, sessionId);
                return;
            }

            for (EpisodicMemoryExtractResult result : results) {
                aiEpisodicMemoryService.saveExtractedMemory(userId, sessionId, result);
            }

            log.info("Episodic Memory 提取完成，userId={}, sessionId={}, count={}",
                    userId, sessionId, results.size());
        } catch (Exception e) {
            log.warn("Episodic Memory 提取失败，userId={}, sessionId={}", userId, sessionId, e);
        }
    }

    private long countSessionMessages(Long sessionId) {
        return aiMessageMapper.selectCount(new LambdaQueryWrapper<AiMessages>()
                .eq(AiMessages::getSessionId, sessionId));
    }

    private List<AiMessages> listRecentWindowMessages(Long sessionId) {
        List<AiMessages> messages = aiMessageMapper.selectList(new LambdaQueryWrapper<AiMessages>()
                .eq(AiMessages::getSessionId, sessionId)
                .orderByDesc(AiMessages::getCreatedAt)
                .last("LIMIT " + WINDOW_MESSAGE_COUNT));

        if (messages == null || messages.isEmpty()) {
            return List.of();
        }

        List<AiMessages> ordered = new ArrayList<>(messages);
        Collections.reverse(ordered);
        return ordered;
    }

    private boolean shouldExtract(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }

        String text = message.replaceAll("\\s+", "");

        return containsAny(text,
                "决定",
                "选择",
                "最终",
                "就用",
                "拍板",
                "完成",
                "上线",
                "已经做完",
                "里程碑",
                "阶段结束",
                "以后准备",
                "后面再做",
                "暂缓",
                "先不做",
                "之前讨论",
                "为什么决定"
        );
    }

    private boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private List<EpisodicMemoryExtractResult> extractByLlm(List<AiMessages> windowMessages) {
        if (windowMessages == null || windowMessages.isEmpty()) {
            return List.of();
        }

        String response = chatClientBuilder.build()
                .prompt()
                .system("""
                        你是一个情景记忆提取器。
                        你的任务是从一段用户与 AI 的历史对话窗口中，提取值得长期保存的历史事件。

                        情景记忆只回答：过去发生了什么、当时为什么这样决定、后续计划是什么。

                        只允许提取四类：
                        - DECISION：已经做出的决定，必须尽量保留原因。
                        - EVENT：发生过的重要事件。
                        - MILESTONE：重要阶段完成或里程碑。
                        - PLAN：明确的后续计划。

                        严格规则：
                        - 不要保存普通问答、闲聊、寒暄、简单报错。
                        - 不要提取用户画像、长期偏好、稳定事实；这些属于 Semantic/User Memory。
                        - 不要保存原始聊天流水，要压缩成一条可读历史事件。
                        - content 必须包含足够上下文，尤其是决策原因。
                        - 如果没有值得保存的事件，返回空数组 []。
                        - shouldRemember=false 的对象不要返回，直接返回空数组或只返回 shouldRemember=true 的对象。
                        - memoryType 只能是 DECISION、EVENT、MILESTONE、PLAN。
                        - importance 范围 1 到 10；低于 6 通常不值得保存。
                        - confidence 范围 0 到 1；低于 0.6 通常不值得保存。
                        - 只能返回 JSON 数组，不要返回 Markdown，不要解释。

                        JSON 格式：
                        [
                          {
                            "shouldRemember": true,
                            "memoryType": "DECISION",
                            "title": "RAG 选用 ES",
                            "content": "用户和 AI 讨论 RAG 方案后，最终决定使用 ES，因为需要同时支持 Keyword Search 和 Vector Search。",
                            "importance": 8,
                            "confidence": 0.91,
                            "sourceMessageIds": [123, 124, 125],
                            "occurredAt": "2026-08-17T21:30:00"
                          }
                        ]
                        """)
                .user("""
                        对话窗口：
                        %s
                        """.formatted(formatWindowMessages(windowMessages)))
                .call()
                .content();

        return parseExtractResult(response);
    }

    private String formatWindowMessages(List<AiMessages> messages) {
        StringBuilder sb = new StringBuilder();

        for (AiMessages message : messages) {
            String role = "user".equals(message.getRole()) ? "用户" : "AI";
            sb.append("messageId=").append(message.getId()).append("\n")
                    .append("createdAt=").append(message.getCreatedAt()).append("\n")
                    .append(role).append("：")
                    .append(limitText(message.getContent(), 1200))
                    .append("\n\n");
        }

        return sb.toString();
    }

    List<EpisodicMemoryExtractResult> parseExtractResult(String response) {
        if (response == null || response.isBlank()) {
            return List.of();
        }

        String json = extractJsonArray(response);
        if (json == null || json.isBlank()) {
            return List.of();
        }

        try {
            return objectMapper.readValue(json, new TypeReference<List<EpisodicMemoryExtractResult>>() {});
        } catch (Exception e) {
            log.warn("Episodic Memory JSON 解析失败，response={}", response, e);
            return List.of();
        }
    }

    String extractJsonArray(String response) {
        int start = response.indexOf("[");
        int end = response.lastIndexOf("]");

        if (start < 0 || end < start) {
            return null;
        }

        return response.substring(start, end + 1);
    }

    private String limitText(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        if (text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength);
    }
}
```

Important: `extractJsonArray` must use `lastIndexOf("]")`, not `indexOf("]")`, because each result contains nested `sourceMessageIds: [123, 124]`.

- [x] **Step 3: Wire extractor into `AiMessageServiceImpl`**

Modify imports are not needed because `com.hailin.blogsystem.service.*` is already imported.

Add a constructor field:

```java
private final AiEpisodicMemoryExtractorService aiEpisodicMemoryExtractorService;
```

In `stopEvent`, right after existing `aiMemoryCandidateExtractorService.extractAfterChat(...)`, add:

```java
aiEpisodicMemoryExtractorService.extractAfterChat(
        userId,
        sessionId,
        userMessage.getId(),
        assistantMessage.getId(),
        message,
        fullReply.toString()
);
```

The full block should become:

```java
// 异步提取候选记忆（规则预筛命中才写入，不会阻塞 SSE）
aiMemoryCandidateExtractorService.extractAfterChat(
        userId,
        sessionId,
        userMessage.getId(),
        message,
        fullReply.toString()
);

// 异步提取情景记忆：记录重要事件、决策、里程碑和计划，不阻塞 SSE
aiEpisodicMemoryExtractorService.extractAfterChat(
        userId,
        sessionId,
        userMessage.getId(),
        assistantMessage.getId(),
        message,
        fullReply.toString()
);
```

- [x] **Step 4: Compile**

Run:

```powershell
cd D:\AIAgent\01myBlog\blog-system
.\mvnw -DskipTests compile
```

Expected: compile passes.

---

### Task 5: Inject episodic prompt into chat and Workflow

**Files:**

- Modify: `blog-system/src/main/java/com/hailin/blogsystem/service/impl/AiPromptServiceImpl.java`
- Modify: `blog-system/src/main/java/com/hailin/blogsystem/ai/workflow/WorkflowKnowledgeSupport.java`

- [x] **Step 1: Inject in normal chat prompt**

Modify `AiPromptServiceImpl`.

Add import:

```java
import com.hailin.blogsystem.service.AiEpisodicMemoryService;
```

Add field:

```java
@Autowired
private AiEpisodicMemoryService aiEpisodicMemoryService;
```

Inside:

```java
if (userId != null && memoryConfig.isEnabled()) {
    String longTermMemoryPrompt = aiUserMemoryService.buildMemoryPrompt(userId,userMessage);
    if (longTermMemoryPrompt != null && !longTermMemoryPrompt.isBlank()) {
        sb.append(longTermMemoryPrompt).append("\n\n---\n\n");
    }
}
```

Change it to:

```java
if (userId != null && memoryConfig.isEnabled()) {
    String longTermMemoryPrompt = aiUserMemoryService.buildMemoryPrompt(userId, userMessage);
    if (longTermMemoryPrompt != null && !longTermMemoryPrompt.isBlank()) {
        sb.append(longTermMemoryPrompt).append("\n\n---\n\n");
    }

    String episodicMemoryPrompt = aiEpisodicMemoryService.buildEpisodicPrompt(userId, userMessage);
    if (episodicMemoryPrompt != null && !episodicMemoryPrompt.isBlank()) {
        sb.append(episodicMemoryPrompt).append("\n\n---\n\n");
    }
}
```

- [x] **Step 2: Inject in Workflow memory context**

Modify `WorkflowKnowledgeSupport`.

Add field:

```java
private final AiEpisodicMemoryService aiEpisodicMemoryService;
```

Update imports:

```java
import com.hailin.blogsystem.service.AiEpisodicMemoryService;
```

Replace `retrieveMemoryContext` with:

```java
public String retrieveMemoryContext(Long userId, String requirement) {
    if (userId == null) {
        return "";
    }

    StringBuilder sb = new StringBuilder();

    try {
        String memoryPrompt = aiUserMemoryService.buildMemoryPrompt(userId, requirement);
        if (memoryPrompt != null && !memoryPrompt.isBlank()) {
            sb.append(memoryPrompt).append("\n\n---\n\n");
        }
    } catch (Exception e) {
        // 记忆检索失败不阻断 Workflow
    }

    try {
        String episodicPrompt = aiEpisodicMemoryService.buildEpisodicPrompt(userId, requirement);
        if (episodicPrompt != null && !episodicPrompt.isBlank()) {
            sb.append(episodicPrompt).append("\n\n---\n\n");
        }
    } catch (Exception e) {
        // 情景记忆检索失败不阻断 Workflow
    }

    return sb.toString().trim();
}
```

- [x] **Step 3: Compile**

Run:

```powershell
cd D:\AIAgent\01myBlog\blog-system
.\mvnw -DskipTests compile
```

Expected: compile passes.

---

### Task 6: Add focused tests

**Files:**
- Create: `blog-system/src/test/java/com/hailin/blogsystem/AiEpisodicMemoryExtractorTests.java`
- Create: `blog-system/src/test/java/com/hailin/blogsystem/AiEpisodicMemoryServiceTests.java`

- [x] **Step 1: Test nested JSON extraction**

Create `blog-system/src/test/java/com/hailin/blogsystem/AiEpisodicMemoryExtractorTests.java`:

```java
package com.hailin.blogsystem;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hailin.blogsystem.service.impl.AiEpisodicMemoryExtractorServiceImpl;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

class AiEpisodicMemoryExtractorTests {

    @Test
    void extractsJsonArrayWithNestedSourceMessageIds() throws Exception {
        AiEpisodicMemoryExtractorServiceImpl extractor = new AiEpisodicMemoryExtractorServiceImpl(
                null,
                null,
                new ObjectMapper(),
                null
        );

        Method method = AiEpisodicMemoryExtractorServiceImpl.class
                .getDeclaredMethod("extractJsonArray", String.class);
        method.setAccessible(true);

        String response = """
                ```json
                [
                  {
                    "shouldRemember": true,
                    "memoryType": "DECISION",
                    "title": "RAG 选用 ES",
                    "content": "用户决定 RAG 使用 ES，因为需要 Keyword + Vector 混合检索。",
                    "importance": 8,
                    "confidence": 0.91,
                    "sourceMessageIds": [123, 124],
                    "occurredAt": "2026-08-17T21:30:00"
                  }
                ]
```
                """;
    
        String json = (String) method.invoke(extractor, response);
    
        assertThat(json).contains("\"sourceMessageIds\": [123, 124]");
        assertThat(json.trim()).startsWith("[");
        assertThat(json.trim()).endsWith("]");
    }
}
```

If Java access complains because `extractJsonArray` is package-private, make it `String extractJsonArray(String response)` and keep the test package as `com.hailin.blogsystem.service.impl`. The important test target is the nested array parsing behavior.

- [ ] **Step 2: Test prompt truncation and header**

Create `blog-system/src/test/java/com/hailin/blogsystem/AiEpisodicMemoryServiceTests.java`:

```java
package com.hailin.blogsystem;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hailin.blogsystem.ai.memory.AiEpisodicMemoryIndexService;
import com.hailin.blogsystem.ai.memory.AiEpisodicMemoryRetrieveService;
import com.hailin.blogsystem.entity.dto.EpisodicMemoryRagContext;
import com.hailin.blogsystem.service.impl.AiEpisodicMemoryServiceImpl;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AiEpisodicMemoryServiceTests {

    @Test
    void buildsSeparatedEpisodicPrompt() {
        AiEpisodicMemoryIndexService indexService = mock(AiEpisodicMemoryIndexService.class);
        AiEpisodicMemoryRetrieveService retrieveService = mock(AiEpisodicMemoryRetrieveService.class);

        when(retrieveService.retrieveForPrompt(eq(1L), eq("01myBlog"), any()))
                .thenReturn(List.of(new EpisodicMemoryRagContext(
                        10L,
                        1L,
                        "01myBlog",
                        "DECISION",
                        "RAG 选用 ES",
                        "用户和 AI 讨论 RAG 方案后，最终决定使用 ES，因为需要同时支持 Keyword Search 和 Vector Search。",
                        new BigDecimal("0.91"),
                        8,
                        LocalDateTime.of(2026, 8, 17, 21, 30)
                )));

        AiEpisodicMemoryServiceImpl service = new AiEpisodicMemoryServiceImpl(
                indexService,
                retrieveService,
                new ObjectMapper()
        );

        String prompt = service.buildEpisodicPrompt(1L, "我们之前为什么决定用 ES？");

        assertThat(prompt).contains("## 历史事件记忆");
        assertThat(prompt).contains("[DECISION]");
        assertThat(prompt).contains("最终决定使用 ES");
        assertThat(prompt).contains("2026-08-17");
    }
}
```

If constructor injection fails because `ServiceImpl` needs Spring context in your local test setup, change this test to `@SpringBootTest` with `@MockBean` dependencies. Keep the assertion target unchanged.

- [x] **Step 3: Run focused tests**

Run:

```powershell
cd D:\AIAgent\01myBlog\blog-system
.\mvnw -Dtest=AiEpisodicMemoryExtractorTests,AiEpisodicMemoryServiceTests test
```

Expected: both test classes pass.

- [x] **Step 4: Run broader backend verification**

Run:

```powershell
cd D:\AIAgent\01myBlog\blog-system
.\mvnw test
```

Expected: existing tests still pass. If unrelated existing tests fail, record the failing class and rerun the focused episodic tests to separate regression from pre-existing failures.

---

## Manual Test Flow

After backend starts and ES is available:

1. Start a logged-in chat session.
2. Send:

```text
RAG 方案我们就决定用 ES 吧，因为我希望同时支持关键词检索和向量检索。
```

3. Wait a few seconds for async extraction.
4. Check MySQL:

```sql
SELECT id, user_id, memory_type, title, content, importance, confidence
FROM ai_episodic_memories
ORDER BY created_at DESC
LIMIT 5;
```

Expected: one `DECISION` memory appears.

5. Ask:

```text
我们之前为什么决定用 ES？
```

Expected: prompt includes `## 历史事件记忆`, and the model can answer the reason.

6. Repeat the first message.

Expected: no duplicate row is inserted because `content_hash` or vector dedupe blocks it.

---

## Rollback Notes

If the feature causes noisy prompt injection:

1. Remove the two prompt injection calls from `AiPromptServiceImpl` and `WorkflowKnowledgeSupport`.
2. Keep table and extraction code temporarily so you can inspect saved rows.
3. If extraction itself is noisy, remove the `aiEpisodicMemoryExtractorService.extractAfterChat(...)` call in `AiMessageServiceImpl`.

This rollback keeps existing Semantic/User Memory untouched.
