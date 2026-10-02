package com.hailin.blogsystem.ai.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hailin.blogsystem.entity.dto.PageContextDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 外层 TaskPlan 快照的**读写编解码**（5b）。
 *
 * <p>为什么要独立一个组件：快照的写入（创建 run 时）与读取（跨请求恢复时）**必须对称**——
 * 写的时候少存一个字段，读出来就是一个语义残缺的计划，而且这种错最难发现：
 * 恢复出来的计划能跑，只是判据少了一条。放在同一个类里，加字段时两边都在眼前。
 *
 * <p><b>快照只存"恢复所需的最小事实</b>"：计划（stepId / 类型 / 参数 / 证据 / 依赖）
 * + 页面锚点。不存文章正文这类会变大的东西，也不存运行结果（结果在
 * {@code ai_agent_steps} 里，那是另一份事实）。
 *
 * <p><b>锚点只从后端校验过的 {@code PageContext} 取</b>，绝不取分类器参数——
 * 恢复时若拿模型的参数去定位文章，等于让模型决定"改哪一篇"。
 *
 * <p>解析失败一律返回 {@code null}：宁可不恢复（外层计划停在可诊断状态），
 * 也不要拿半份计划去执行——那会把"缺字段"变成"做错事"。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TaskPlanSnapshotCodec {

    /** 快照结构版本：加字段不必升版本（读取端对缺失字段有默认值），改语义才升 */
    private static final int SCHEMA_VERSION = 1;

    private final ObjectMapper objectMapper;

    /** 计划 + 页面锚点 = 恢复一份外层计划所需的全部输入 */
    public record Snapshot(TaskPlan plan, PageContextDTO anchor) {
    }

    public String write(TaskPlan plan, PageContextDTO pageContext) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("schemaVersion", SCHEMA_VERSION);

        Map<String, Object> planPart = new LinkedHashMap<>();
        planPart.put("goal", plan.goal());
        List<Map<String, Object>> steps = new ArrayList<>();
        for (TaskStep step : plan.steps()) {
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("stepId", step.stepId());
            one.put("type", step.type() == null ? null : step.type().name());
            one.put("params", step.input());
            one.put("goalEvidence", step.goalEvidence());
            StepDependency dependency = step.dependency();
            if (dependency != null) {
                one.put("after", dependency.after());
                one.put("requires", dependency.requires());
                one.put("onFailure", dependency.onFailure() == null ? null : dependency.onFailure().name());
            }
            steps.add(one);
        }
        planPart.put("steps", steps);
        planPart.put("uncoveredGoalEvidence", plan.uncoveredGoalEvidence());
        // 未覆盖目标的**类型**也要存：路由判据与"不得谎报完成"都依赖它，
        // 只存证据文本的话，恢复出来的计划会丢掉这条语义
        planPart.put("uncoveredTypes", plan.uncoveredTypes());
        snapshot.put("plan", planPart);

        Map<String, Object> anchor = new LinkedHashMap<>();
        anchor.put("pageType", pageContext == null ? null : pageContext.getPageType());
        anchor.put("articleId", pageContext == null ? null : pageContext.getArticleId());
        snapshot.put("anchor", anchor);

        return toJson(snapshot);
    }

    /** @return 读不出来（缺 goal / 步骤非法 / JSON 损坏）返回 {@code null} */
    public Snapshot read(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode planNode = root.path("plan");
            String goal = textOrNull(planNode.path("goal"));
            if (goal == null || goal.isBlank()) {
                log.warn("[TASK-SNAPSHOT] 快照缺少 goal，放弃恢复");
                return null;
            }
            List<TaskStep> steps = new ArrayList<>();
            for (JsonNode stepNode : planNode.path("steps")) {
                TaskStep step = readStep(stepNode);
                if (step == null) {
                    log.warn("[TASK-SNAPSHOT] 快照里有步骤读不出来，放弃整份恢复");
                    return null;
                }
                steps.add(step);
            }
            TaskPlan plan = new TaskPlan(goal, steps,
                    readStringList(planNode.path("uncoveredGoalEvidence")),
                    readStringList(planNode.path("uncoveredTypes")));
            return new Snapshot(plan, readAnchor(root.path("anchor")));
        } catch (Exception e) {
            log.warn("[TASK-SNAPSHOT] 快照解析失败，放弃恢复", e);
            return null;
        }
    }

    private TaskStep readStep(JsonNode node) {
        String stepId = textOrNull(node.path("stepId"));
        StepType type = parseType(textOrNull(node.path("type")));
        if (stepId == null || stepId.isBlank() || type == null) {
            return null;
        }
        StepDependency dependency = new StepDependency(
                readStringList(node.path("after")),
                readStringList(node.path("requires")),
                parseOnFailure(textOrNull(node.path("onFailure"))));
        return new TaskStep(stepId, type, readMap(node.path("params")), dependency,
                readStringList(node.path("goalEvidence")));
    }

    private static StepType parseType(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return StepType.valueOf(raw.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** 未知 / 缺失一律 null —— 构造器会落回 {@code OnFailure.CONTINUE}，不猜"更安全"的策略 */
    private static OnFailure parseOnFailure(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return OnFailure.valueOf(raw.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static PageContextDTO readAnchor(JsonNode node) {
        PageContextDTO page = new PageContextDTO();
        if (node != null && !node.isMissingNode() && !node.isNull()) {
            page.setPageType(textOrNull(node.path("pageType")));
            page.setArticleId(textOrNull(node.path("articleId")));
        }
        return page;
    }

    private static List<String> readStringList(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (JsonNode one : node) {
            if (one != null && !one.isNull()) {
                values.add(one.asText());
            }
        }
        return values;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readMap(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return Map.of();
        }
        return objectMapper.convertValue(node, Map.class);
    }

    private static String textOrNull(JsonNode node) {
        return node == null || node.isMissingNode() || node.isNull() ? null : node.asText();
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            log.warn("[TASK-SNAPSHOT] 快照序列化失败", e);
            return "{}";
        }
    }
}
