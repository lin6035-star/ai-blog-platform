package com.hailin.blogsystem.ai.rag.index;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.ResponseException;
import org.elasticsearch.client.RestClient;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 文章 RAG 的物理索引和 active alias 管理器。
 *
 * <p>它不负责切块、embedding 或批量写文档，只负责索引边界，避免重建时清空在线索引。
 */
@Component
public class ArticleRagIndexManager {

    public static final String ACTIVE_ALIAS = "article_rag_active";
    private static final String MAPPING_RESOURCE = "elasticsearch/article-rag-mapping.json";
    private static final DateTimeFormatter INDEX_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final Pattern UNSAFE_NAME = Pattern.compile("[^a-z0-9-]+");

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Autowired
    public ArticleRagIndexManager(RestClient restClient, ObjectMapper objectMapper) {
        this(restClient, objectMapper, Clock.systemUTC());
    }

    public ArticleRagIndexManager(RestClient restClient, ObjectMapper objectMapper, Clock clock) {
        this.restClient = restClient;
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public String physicalIndexName(String embeddingModel, int dimensions, String chunkingVersion) {
        return "article-rag-"
                + normalizeName(embeddingModel)
                + "-" + dimensions
                + "-" + normalizeName(chunkingVersion)
                + "-" + LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).format(INDEX_TIMESTAMP);
    }

    public String mappingJson() {
        return mappingJson(1024);
    }

    public String mappingJson(int dimensions) {
        if (dimensions <= 0) {
            throw new IllegalArgumentException("dimensions 必须大于 0");
        }
        try (InputStream input = new ClassPathResource(MAPPING_RESOURCE).getInputStream()) {
            String json = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            return json.replace("\"dims\": 1024", "\"dims\": " + dimensions);
        } catch (IOException e) {
            throw new IllegalStateException("读取文章 RAG mapping 失败: " + MAPPING_RESOURCE, e);
        }
    }

    public void createPhysicalIndex(String physicalIndexName, int dimensions) throws IOException {
        requireRestClient();
        Request request = new Request("PUT", "/" + physicalIndexName);
        request.setJsonEntity(mappingJson(dimensions));
        restClient.performRequest(request);
    }

    public void switchActiveAlias(String physicalIndexName, List<String> currentTargets) throws IOException {
        requireRestClient();
        Request request = new Request("POST", "/_aliases");
        request.setJsonEntity(aliasSwitchBody(physicalIndexName, currentTargets));
        restClient.performRequest(request);
    }

    public String aliasSwitchBody(String physicalIndexName, List<String> currentTargets) {
        List<Map<String, Object>> actions = new ArrayList<>();
        for (String target : currentTargets == null ? List.<String>of() : currentTargets) {
            if (target == null || target.isBlank()) {
                continue;
            }
            actions.add(Map.of("remove", Map.of(
                    "index", target,
                    "alias", ACTIVE_ALIAS,
                    "must_exist", false
            )));
        }
        actions.add(Map.of("add", Map.of(
                "index", physicalIndexName,
                "alias", ACTIVE_ALIAS,
                "is_write_index", true
        )));
        try {
            return objectMapper.writeValueAsString(Map.of("actions", actions));
        } catch (IOException e) {
            throw new IllegalStateException("构造文章 RAG alias 切换请求失败", e);
        }
    }

    /** 读取当前 alias 指向，供调用方在同一次切换前形成 remove actions。 */
    public List<String> currentAliasTargets() throws IOException {
        requireRestClient();
        try {
            Response response = restClient.performRequest(new Request("GET", "/_alias/" + ACTIVE_ALIAS));
            if (response.getEntity() == null) {
                return List.of();
            }
            try (InputStream input = response.getEntity().getContent()) {
                Map<?, ?> root = objectMapper.readValue(input, Map.class);
                return root.keySet().stream().map(Object::toString).toList();
            }
        } catch (ResponseException e) {
            if (e.getResponse().getStatusLine().getStatusCode() == 404) {
                return List.of();
            }
            throw e;
        }
    }

    public ArticleRagIndexManifest manifest(
            String physicalIndexName,
            String embeddingModel,
            int dimensions,
            String chunkingVersion,
            long articleCount,
            long chunkCount,
            String contentHash
    ) {
        return new ArticleRagIndexManifest(
                physicalIndexName,
                embeddingModel,
                dimensions,
                chunkingVersion,
                clock.instant(),
                articleCount,
                chunkCount,
                contentHash
        );
    }

    private String normalizeName(String value) {
        String normalized = value == null ? "unknown" : value.toLowerCase(Locale.ROOT).trim();
        normalized = UNSAFE_NAME.matcher(normalized).replaceAll("-");
        normalized = normalized.replaceAll("-+", "-");
        return normalized.replaceAll("^-|-$", "");
    }

    private void requireRestClient() {
        if (restClient == null) {
            throw new IllegalStateException("ArticleRagIndexManager 未配置 RestClient");
        }
    }
}
