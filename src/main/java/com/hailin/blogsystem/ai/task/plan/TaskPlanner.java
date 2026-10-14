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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
        int firstHandoffIndex = firstGoalIndex(goals, StepType.WORKFLOW_HANDOFF);

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

            // 5b 第一版的恢复出口没有原 SSE eventSink，也不重新抢长任务准入。
            // 因此 handoff 之后只能续跑纯后端只读的 ARTICLE_SEARCH；判断必须按候选位置，
            // 不能依赖 after/requires——暂停本身就会让后面的所有步骤进入恢复链。
            if (firstHandoffIndex >= 0 && i > firstHandoffIndex && type != StepType.ARTICLE_SEARCH) {
                log.info("[TASK-PLAN] handoff 后置步骤超出 5b 边界，按未覆盖处理: candidate=s{}, type={}",
                        i + 1, type);
                continue;
            }

            // 依赖引用无法解析时不能静默删除，否则 requires 会退化成无条件执行，
            // after 也会丢失用户明确表达的顺序约束。只接受 sN 且指向另一候选。
            if (hasInvalidDependencyReference(goal, i, goals.size())) {
                // 必须打出原始引用：排查这次问题的最大障碍就是日志只说"解析失败"、
                // 不说模型到底写了什么，只能靠猜。脱敏后照原样落盘。
                log.info("[TASK-PLAN] 依赖引用无法解析，按未覆盖处理: candidate=s{}, refs={}",
                        i + 1, LogValues.safe(dependencyRefs(goal).toList().toString()));
                continue;
            }

            // C0 收尾：显式给了但无法解析的 onFailure 不能静默降级成 CONTINUE——
            // 那等于把"失败就停"执行成"失败也照常跑"
            if (parseOnFailureOrNull(goal.onFailure()) == null) {
                log.info("[TASK-PLAN] onFailure 无法解析，按未覆盖处理: candidate=s{}, raw={}",
                        i + 1, LogValues.safe(goal.onFailure()));
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

    private int firstGoalIndex(List<TaskPlanCandidate.CandidateGoal> goals, StepType expected) {
        for (int i = 0; i < goals.size(); i++) {
            if (parseType(goals.get(i).type()) == expected) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 依赖映射（C0.1 修订）。
     *
     * <p>候选里的 {@code after}/{@code requires} 是**模型自由文本**：这里先归一化成标准
     * {@code sN} 再交给下游——{@code TaskOrchestrator} 按 {@code sN} 匹配上下游，
     * 不归一化的话 {@code "s2、s3"} 会因为对不上而被**静默丢掉依赖**。
     *
     * <p>进来时 {@code accepted} 已保证**被引用的候选一定可承接**——
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

        List<String> after = normalizeStepRefs(goal.after(), valid);
        List<String> requires = normalizeStepRefs(goal.requires(), valid);
        // 第一遍扫描已保证：能走到这里的目标，onFailure 一定可解析（无法解析的已按未覆盖处理）
        OnFailure onFailure = parseOnFailureOrNull(goal.onFailure());
        if (onFailure == null) {
            onFailure = OnFailure.CONTINUE;
        }
        // requires 是硬约束：拿不到数据必须跳过，不能被 onFailure 覆盖（与 Orchestrator 的判定顺序一致）
        if (!requires.isEmpty()) {
            onFailure = OnFailure.SKIP;
        }
        return new StepDependency(after, requires, onFailure);
    }

    /**
     * 自由文本引用 → 标准 {@code sN}，并滤掉指向"未被接受候选"的引用。
     *
     * <p>归一是必须的：下游按 {@code sN} 字符串匹配上下游，{@code "s2、s3"} 原样透传会因为对不上
     * 而被当成"没有依赖"——那等于把用户明确说的顺序**静默丢掉**。
     *
     * <p>能走到这里的目标，其引用已过 {@link #hasInvalidDependencyReference} 校验，
     * 所以过滤只会滤掉"被连带拒绝的候选"那一类，而那种情况下该目标自己也已经被标成未覆盖了。
     */
    private static List<String> normalizeStepRefs(List<String> refs, Set<String> validStepIds) {
        return refs.stream()
                .flatMap(ref -> candidateIndexesFromTaskRef(ref).stream())
                .map(index -> "s" + index)
                .filter(validStepIds::contains)
                .distinct()
                .toList();
    }

    /**
     * 候选是否依赖了「被拒绝的候选」。
     *
     * <p>这类引用**能识别就必须处理**：静默丢弃会让步骤从"等前置数据"变成"无条件执行"，
     * 而它等的可能是一个参数非法、根本不该跑的步骤。
     */
    private boolean dependsOnRejected(TaskPlanCandidate.CandidateGoal goal, boolean[] accepted) {
        return dependencyRefs(goal)
                .flatMap(ref -> candidateIndexesFromTaskRef(ref).stream())
                .anyMatch(idx -> idx >= 1 && idx <= accepted.length && !accepted[idx - 1]);
    }

    private boolean hasInvalidDependencyReference(TaskPlanCandidate.CandidateGoal goal,
                                                   int candidateIndex,
                                                   int candidateCount) {
        for (String ref : dependencyRefs(goal).toList()) {
            if (ref == null || ref.isBlank()) {
                continue;
            }
            List<Integer> indexes = candidateIndexesFromTaskRef(ref);
            // 一个序号都抽不出来 = 无法建立可靠映射 → 仍然非法。
            // 这条是底线：静默丢弃会让 requires 退化成无条件执行（见 candidateIndexesFromTaskRef 的注释）
            if (indexes.isEmpty()) {
                return true;
            }
            for (int index : indexes) {
                if (index < 1 || index > candidateCount || index == candidateIndex + 1) {
                    return true;
                }
            }
        }
        return false;
    }

    /** {@code after} + {@code requires} 的原始引用串（模型自由文本） */
    private static Stream<String> dependencyRefs(TaskPlanCandidate.CandidateGoal goal) {
        return Stream.concat(goal.after().stream(), goal.requires().stream());
    }

    /**
     * 引用 → 步骤序号（2026-10-12 放宽格式）。
     *
     * <p><b>为什么放宽</b>：原先只认规整的 {@code "s2"}——模型一旦写成 {@code "s2、s3"} /
     * {@code "第2步"} / {@code "step 2"}，就被判成"无法建立可靠映射"，进而**整条请求什么都做不了**。
     * 实测代价：「取消点赞并收藏这篇文章，再搜一下 Java 相关文章，并比较各篇文章」因为这种写法被拒，
     * 而语义相同的「…并横向分析一下各篇文章」能正常跑——**一个格式问题升级成了功能不可用**。
     *
     * <p><b>放宽的是格式，不是约束</b>：抽出来的序号仍然逐个校验"在候选范围内、不是自己"；
     * 一个序号都抽不出来时依旧判非法——那正是"顺序约束退化成无条件执行"的来源，不能碰。
     *
     * <p>一个引用里允许写多个步骤：{@code s2、s3} / {@code s2,s3} / {@code s2 和 s3}。
     */
    private static List<Integer> candidateIndexesFromTaskRef(String ref) {
        if (ref == null || ref.isBlank()) {
            return List.of();
        }
        List<Integer> indexes = new ArrayList<>();
        for (String part : ref.trim().split("[,，、;；/\\s]+|和|与")) {
            Integer index = candidateIndexFromSingleRef(part);
            if (index != null) {
                indexes.add(index);
            }
        }
        return indexes;
    }

    /**
     * 单个引用片段 → 序号。
     *
     * <p>刻意只认三种有明确步骤语义的写法，**不从长文本里 grep 数字**：
     * 否则 {@code "articleId 2085208555163787269"} 这类参数会被误读成步骤引用。
     *
     * <p>裸数字（{@code "2"}）接受，因为模型有时只写序号；误判方向也是**更保守**的
     * ——多出一个依赖只会让顺序更严格，不会让约束消失。越界的裸数字照样判非法。
     */
    private static Integer candidateIndexFromSingleRef(String part) {
        if (part == null || part.isBlank()) {
            return null;
        }
        String normalized = part.trim().toLowerCase(Locale.ROOT);

        Matcher prefixed = STEP_REF_WITH_PREFIX.matcher(normalized);
        if (prefixed.matches()) {
            return Integer.parseInt(prefixed.group(1));
        }
        Matcher bare = STEP_REF_BARE.matcher(normalized);
        return bare.matches() ? Integer.parseInt(bare.group(1)) : null;
    }

    /** {@code s2} / {@code step 2} / {@code 第2步} / {@code 步骤2} */
    private static final Pattern STEP_REF_WITH_PREFIX =
            Pattern.compile("^(?:s|step|第|步骤)\\s*(\\d+)\\s*步?$");

    /** 裸序号：{@code "2"} 也当作第二步 */
    private static final Pattern STEP_REF_BARE = Pattern.compile("^(\\d+)$");

    /**
     * 解析 {@code onFailure}（C0 收尾）。
     *
     * <p>三态必须分开：
     * <ul>
     *   <li><b>未提供</b>（字段缺失 / null / 空白）→ 默认 {@link OnFailure#CONTINUE}；</li>
     *   <li><b>显式给了但解析不了</b>（如模型编出来的 {@code STOP}）→ 返回 {@code null}，
     *       由调用方按"校验失败"处理。</li>
     * </ul>
     *
     * <p>原先这种情况静默回落 {@code CONTINUE}，语义正好相反：用户要求"失败就停"，
     * 实际变成"失败也照常执行"。这比 {@code changeTitle} 那类"拒绝执行"更危险——
     * 它会**错误地继续执行**。
     */
    private static OnFailure parseOnFailureOrNull(String raw) {
        if (raw == null || raw.isBlank()) {
            return OnFailure.CONTINUE;
        }
        try {
            return OnFailure.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
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
