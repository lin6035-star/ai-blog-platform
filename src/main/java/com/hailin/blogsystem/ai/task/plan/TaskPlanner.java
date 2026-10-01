package com.hailin.blogsystem.ai.task.plan;

import com.hailin.blogsystem.ai.LogValues;
import com.hailin.blogsystem.ai.task.OnFailure;
import com.hailin.blogsystem.ai.task.StepDependency;
import com.hailin.blogsystem.ai.task.StepType;
import com.hailin.blogsystem.ai.task.TaskPlan;
import com.hailin.blogsystem.ai.task.TaskStep;
import com.hailin.blogsystem.ai.task.TaskStepExecutorRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 任务计划校验器（第三刀·2c）——**候选计划 → 权威计划**。
 *
 * <p>它是"后端判事实"的落点：模型只提候选，白名单 / 类型识别 / 证据校验 / 冲突检查全在这里。
 * 放在独立组件里而不是让 {@code AiMessageServiceImpl} 自己判断，是因为**"这个组合支不支持"
 * 是编排层的知识，不该散进聊天入口**。
 *
 * <p>三条校验规则：
 * <ol>
 *   <li><b>类型必须是封闭集合里的、且有执行器承接</b>——不认识或没执行器的记入
 *       {@code uncoveredGoalEvidence}，**不丢弃**（丢弃就是静默丢目标）。</li>
 *   <li><b>证据必须真的在用户原话里</b>——整条拒绝，而不是只丢掉那一步：
 *       证据不成立说明模型的拆解本身不可信。</li>
 *   <li><b>同对象上的相反动作必须拒绝</b>（同时点赞和取消点赞），与第一刀一致。</li>
 * </ol>
 *
 * <p><b>它不决定"退回旧路径"</b>：那属于路由层的策略（设计稿 §5.1），
 * Planner 只回答"这份候选能不能变成一份可执行的计划"。
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class TaskPlanner {

    /** 同对象上的相反动作——同时出现即冲突 */
    private static final List<Set<String>> OPPOSITE_ACTIONS = List.of(
            Set.of("likeArticle", "unlikeArticle"),
            Set.of("favoriteArticle", "unfavoriteArticle"),
            Set.of("followAuthor", "unfollowAuthor"));

    private final TaskStepExecutorRegistry registry;

    public PlanningResult plan(String message, TaskPlanCandidate candidate) {
        if (candidate == null) {
            return PlanningResult.notTask("没有候选计划");
        }
        if (candidate.requestKind() == TaskPlanCandidate.RequestKind.CHAT) {
            return PlanningResult.notTask("不是任务请求");
        }
        if (candidate.goals().isEmpty()) {
            return PlanningResult.notTask("没有识别出可执行目标");
        }

        List<TaskStep> steps = new ArrayList<>();
        List<String> uncovered = new ArrayList<>(candidate.uncoveredGoalEvidence());
        // 记录未覆盖目标的**类型**——路由判据要据此决定"旧路径能不能完整处理"
        List<String> uncoveredTypes = new ArrayList<>();
        List<TaskPlanCandidate.CandidateGoal> goals = candidate.goals();

        // ① 逐条判定能否承接（证据 / 类型 / 执行器 / 关键参数）——**先只判定、不建步骤**。
        //    C0.1 起必须两遍：stepId 按候选序号分配，而"哪些候选能承接"又决定依赖判定的结果。
        boolean[] accepted = new boolean[goals.size()];
        for (int i = 0; i < goals.size(); i++) {
            TaskPlanCandidate.CandidateGoal goal = goals.get(i);

            // 证据必须真的在原文里——不成立就整条拒绝（模型的拆解不可信）
            if (!evidenceInMessage(message, goal.goalEvidence())) {
                log.info("[TASK-PLAN] 目标证据不在原文中，拒绝整份候选: type={}, evidence={}",
                        LogValues.safe(goal.type()), LogValues.safe(String.valueOf(goal.goalEvidence())));
                return PlanningResult.cta("我理解的目标和你说的话对不上，能再说具体一点吗？");
            }

            // 类型必须认识 + 有执行器承接；否则记为"未覆盖"而不是丢弃
            StepType type = parseType(goal.type());
            if (type == null || !registry.supports(type)) {
                continue;
            }

            // 依赖引用无法解析时不能静默删除，否则 requires 会退化成无条件执行，
            // after 也会丢失用户明确表达的顺序约束。只接受 sN 且指向另一候选。
            if (hasInvalidDependencyReference(goal, i, goals.size())) {
                log.info("[TASK-PLAN] 依赖引用无法解析，按未覆盖处理: candidate=s{}", i + 1);
                continue;
            }

            // C0.1：关键参数二级校验——不以 prompt 约束代替后端校验
            String paramError = TaskStepParamValidator.validate(type, goal.params());
            if (paramError != null) {
                log.info("[TASK-PLAN] 步骤参数非法，按未覆盖处理: candidate=s{}, type={}, reason={}",
                        i + 1, type, paramError);
                continue;
            }
            accepted[i] = true;
        }

        // ② C0.1：依赖连带——引用了「不可承接候选」的目标同样不可执行。
        //    不能像以前那样静默丢掉这个引用：那会把"等 s2 的数据"悄悄变成"无条件执行"。
        boolean changed = true;
        while (changed) {
            changed = false;
            for (int i = 0; i < goals.size(); i++) {
                if (accepted[i] && dependsOnRejected(goals.get(i), accepted)) {
                    log.info("[TASK-PLAN] 依赖了不可承接的步骤，连带按未覆盖处理: candidate=s{}", i + 1);
                    accepted[i] = false;
                    changed = true;
                }
            }
        }

        // ③ 按**候选序号**建步骤：过滤不引起重排（否则后面的步骤会顶替被过滤者的编号，
        //    别的目标写 s2 就会指错对象）
        for (int i = 0; i < goals.size(); i++) {
            TaskPlanCandidate.CandidateGoal goal = goals.get(i);
            if (!accepted[i]) {
                uncovered.addAll(goal.goalEvidence());
                uncoveredTypes.add(goal.type() == null ? "" : goal.type());
                continue;
            }
            steps.add(new TaskStep(
                    "s" + (i + 1),
                    parseType(goal.type()),
                    goal.params(),
                    dependency(goal, accepted, goals),
                    goal.goalEvidence()));
        }

        // 注意：这里**不因为 steps 为空就 CTA**——"分析这篇文章"这类单任务未迁移，
        // 旧兼容路径是能完整处理的。走哪条路由由 TaskPlanningFacade 按未覆盖目标的类型决定，
        // 校验器只负责把事实（哪些能承接、哪些不能）摆出来。

        // ③ 同对象相反动作冲突
        String conflict = detectConflict(steps);
        if (conflict != null) {
            return PlanningResult.cta(conflict);
        }

        TaskPlan plan = new TaskPlan(candidate.goal(), steps, uncovered, uncoveredTypes);
        log.info("[TASK-PLAN] 计划生成: steps={}, uncovered={}", steps.size(), uncovered.size());
        return PlanningResult.task(plan);
    }

    /** 证据只要有一条不在原文里就判失败——模型改写过的目标不可信 */
    private boolean evidenceInMessage(String message, List<String> evidence) {
        if (evidence.isEmpty()) {
            return false;
        }
        String normalized = message == null ? "" : message.replaceAll("\\s+", "");
        for (String item : evidence) {
            if (item == null || item.isBlank()) {
                return false;
            }
            if (!normalized.contains(item.replaceAll("\\s+", ""))) {
                return false;
            }
        }
        return true;
    }

    private StepType parseType(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return StepType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * 依赖映射（C0.1 修订）。
     *
     * <p>候选里的 {@code after}/{@code requires} 是**模型自由文本**——只有 {@code sN} 形式能与步骤
     * 建立可靠映射（"第一步" / 类型名这类无从确定指向，现状也是被丢弃的）。
     *
     * <p>与修订前的关键差别：进来时 {@code accepted} 已保证**被引用的候选一定可承接**——
     * 引用不可承接候选的步骤在上一轮已被连带拒绝。所以这里不再承担"丢弃失效引用"的职责，
     * 也就不会再把依赖硬约束静默降级成无条件执行。
     */
    private StepDependency dependency(TaskPlanCandidate.CandidateGoal goal, boolean[] accepted,
                                      List<TaskPlanCandidate.CandidateGoal> goals) {
        Set<String> valid = new LinkedHashSet<>();
        for (int i = 0; i < goals.size(); i++) {
            if (accepted[i]) {
                valid.add("s" + (i + 1));
            }
        }

        List<String> after = goal.after().stream().map(String::trim).filter(valid::contains).toList();
        List<String> requires = goal.requires().stream().map(String::trim).filter(valid::contains).toList();
        OnFailure onFailure = parseOnFailure(goal.onFailure());
        // requires 是硬约束：拿不到数据必须跳过，不能被 onFailure 覆盖（与 Orchestrator 的判定顺序一致）
        if (!requires.isEmpty()) {
            onFailure = OnFailure.SKIP;
        }
        return new StepDependency(after, requires, onFailure);
    }

    /**
     * 候选是否依赖了「被拒绝的候选」——只识别 {@code sN} 形式。
     *
     * <p>这类引用**能识别就必须处理**：静默丢弃会让步骤从"等前置数据"变成"无条件执行"，
     * 而它等的可能是一个参数非法、根本不该跑的步骤。
     */
    private boolean dependsOnRejected(TaskPlanCandidate.CandidateGoal goal, boolean[] accepted) {
        return Stream.concat(goal.after().stream(), goal.requires().stream())
                .map(TaskPlanner::candidateIndexFromTaskRef)
                .anyMatch(idx -> idx >= 1 && idx <= accepted.length && !accepted[idx - 1]);
    }

    private boolean hasInvalidDependencyReference(TaskPlanCandidate.CandidateGoal goal,
                                                   int candidateIndex,
                                                   int candidateCount) {
        return Stream.concat(goal.after().stream(), goal.requires().stream())
                .filter(ref -> ref != null && !ref.isBlank())
                .map(TaskPlanner::candidateIndexFromTaskRef)
                .anyMatch(index -> index < 1 || index > candidateCount || index == candidateIndex + 1);
    }

    /** {@code "s2"} → 2；其他形式（类型名 / 自然语言 / null）→ -1（无法建立可靠映射） */
    private static int candidateIndexFromTaskRef(String ref) {
        if (ref == null) {
            return -1;
        }
        String trimmed = ref.trim();
        if (trimmed.length() < 2 || (trimmed.charAt(0) != 's' && trimmed.charAt(0) != 'S')) {
            return -1;
        }
        try {
            return Integer.parseInt(trimmed.substring(1));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private OnFailure parseOnFailure(String raw) {
        if (raw == null || raw.isBlank()) {
            return OnFailure.CONTINUE;
        }
        try {
            return OnFailure.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return OnFailure.CONTINUE;
        }
    }

    private String detectConflict(List<TaskStep> steps) {
        Set<String> actions = new LinkedHashSet<>();
        for (TaskStep step : steps) {
            if (step.type() != StepType.ARTICLE_WRITE) {
                continue;
            }
            Object action = step.input().get("action");
            if (action != null) {
                actions.add(String.valueOf(action));
            }
        }
        for (Set<String> pair : OPPOSITE_ACTIONS) {
            if (actions.containsAll(pair)) {
                return "你同时要求了两种相反的操作，我先不做——请明确要以哪个为准。";
            }
        }
        return null;
    }
}
