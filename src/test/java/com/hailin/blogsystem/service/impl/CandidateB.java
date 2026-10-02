package com.hailin.blogsystem.service.impl;

import com.hailin.blogsystem.ai.ClassifierProbe;
import com.hailin.blogsystem.ai.TokenUsageAccumulator;
import com.hailin.blogsystem.entity.LearningPlans;
import com.hailin.blogsystem.entity.dto.AiIntent;
import com.hailin.blogsystem.entity.dto.PageContextDTO;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * P3 候选 B：**Route + Extractor 双调用**（设计稿 §6.2 / §6.3，2026-10-01）。
 *
 * <p><b>它和 A 的分歧点在哪</b>：A 用**关键词线索**决定装哪些域，B 用**模型路由**决定。
 * 线索表的先天问题是它只能覆盖"写进表里的说法"——没写进去的说法一律 fail-open 拼回 FULL，
 * 所以 A 的降幅上限被"敢不敢认"卡住（26 条里 6 条只能走 FULL）。B 把"认域"这件事交给模型，
 * 换来两个结构性收益：
 * <ol>
 *   <li><b>纯闲聊只付一次小调用</b>：路由段判成纯 CHAT 就直接收口，不再发起第二次调用
 *       ——A 在这种情况下因为 0 线索只能拼回 FULL（每条约 8400 token）；</li>
 *   <li><b>多目标不再靠词表碰运气</b>：{@code candidateDomains} 是数组，复合请求天然多标签。</li>
 * </ol>
 *
 * <p><b>它的结构性代价</b>（设计稿 §16.1 已警告，实测要看数）：需要字段提取的请求是
 * **两次调用**——第二次还要背上字段契约（{@code EXAMPLES_AND_SCHEMA} 占整个 prompt 的 27%），
 * 所以业务请求的 token 未必比 A 省，而**调用次数翻倍正踩在供应商限流的真瓶颈上**。
 * 这一轮就是来量这个的，不预设胜者。
 *
 * <p><b>硬约束</b>（§16.4）：第一段**不得把多目标压成单值**——所以输出的是
 * {@code candidateDomains[]} 而不是单个 intent。
 */
final class CandidateB {

    private CandidateB() {
    }

    private static final String DOMAIN_CHAT = "CHAT";

    /**
     * 第二段的装载表：域 → 该域要装的块。
     *
     * <p>公共块（字段契约在内）由 {@link CandidateA#assembleWithCommon} 统一带上——
     * 与被测的候选 A 共用**同一份**契约文本与拼装顺序，否则两边的差值里会混进"契约不一样"。
     */
    private static final Map<String, List<String>> BLOCKS_BY_DOMAIN = Map.of(
            DOMAIN_CHAT, List.of(),
            "ARTICLE", List.of("ARTICLE_CONTENT"),
            "ARTICLE_SEARCH", List.of("SEARCH"),
            "NAVIGATE", List.of("NAVIGATE"),
            "CREATE_ARTICLE", List.of("CREATE_ARTICLE"),
            "OPTIMIZE_ARTICLE", List.of("ARTICLE_OPTIMIZE"),
            "LEARNING", List.of("LEARNING_PLAN", "LEARNING_AGENT"),
            "EDITOR", List.of("EDITOR", "PAGE_ACTION_RULES"),
            "WALLET", List.of("WALLET"),
            "PROFILE", List.of());

    /** 全部可选域名——**从装载表派生**，避免"prompt 里列了、装载表却没有"这种静默退化 */
    static final List<String> DOMAINS = BLOCKS_BY_DOMAIN.keySet().stream().sorted().toList();

    /** 第二段的 prompt：公共块（字段契约在内）+ 该域的规则块。包私有供离线形状检查量字符数 */
    static String extractorPrompt(List<String> domains) {
        return CandidateA.assembleWithCommon(blocksOf(domains));
    }

    /** 第一段：路由分诊。**只判域**，不做字段提取、不回答用户 */
    static final String ROUTE_PROMPT = """
            你是请求分诊器：**只判断这条请求涉及哪些领域**，不回答用户、不提取任何业务字段。

            可选领域（**可多选**，涉及几个就列几个）：
            CHAT             闲聊、概念问答、技术方案讨论、项目咨询
            ARTICLE          当前文章的问答 / 评价 / 分析，或对文章执行动作（点赞、收藏、关注、隐藏、公开、滚动、复制链接）
            ARTICLE_SEARCH   在站内找文章（"有没有…的文章""找找…"）
            NAVIGATE         跳转页面（作者主页、首页、编辑器、草稿箱、排行榜）
            CREATE_ARTICLE   写一篇新文章
            OPTIMIZE_ARTICLE 优化当前文章（润色、重写、删改段落、把标题改短）
            LEARNING         学习计划相关（查进度、制定计划、调整阶段、把难点拆成任务、分析计划）
            EDITOR           写文章页面里保存草稿 / 发布
            WALLET           钱包充值、余额
            PROFILE          某人的主页信息（发过什么文章、点赞收藏情况）

            输出（纯 JSON，禁止任何解释、注释、markdown 代码块标记）：
            {"candidateDomains":["CHAT"],"requestKind":"CHAT","confidence":0.9,"needsThinking":false,"needsThinkingReason":null,"reason":"简短理由"}

            字段说明：
            - candidateDomains：上面那张表里的域名，**多目标请求要把每个目标涉及的领域都列上**
            - requestKind：要执行动作 / 跑流程 = "TASK"；只是问答 / 讨论 = "CHAT"
            - confidence：0~1 的小数
            - needsThinking：问题依赖前文状态、需要先查再答时 true（如"结合我最近的情况，给个建议"）；纯知识问答 false
            - needsThinkingReason：needsThinking 为 true 时写原因，否则 null

            判定规则：
            1. **多目标都要列**："先 A，再 B""A，顺便 B"这类复合请求，把每个子目标涉及的领域都列上。
            2. **宁多勿少**：判不准就把可能的领域一起列上——多列一个领域只是多花一点 Token，
               **漏列会让下游丢掉该领域的判定规则，直接判错**。
            3. **pageContext 只是线索，不是边界**：用户在文章详情页也可能问学习计划、钱包或写新文章。
            4. 纯概念问答（"什么是缓存穿透"）只列 CHAT。
            5. 找站内文章（"有没有 XX 相关的文章"）是 ARTICLE_SEARCH，不是 CHAT。
            6. "分析 / 评价某篇文章的内容"是 ARTICLE；"制定 / 调整 / 攻坚学习计划"是 LEARNING。

            """;

    /**
     * 路由段的 repair（自包含）。
     *
     * <p>现状那条 {@code REPAIR} 只写"字段与第一次要求完全一致"，但模型在 repair 轮根本看不到
     * "第一次的要求"（§6.6 登记的基线缺口）。新协议的做法是**把 schema 写进 repair**——
     * 路由段只有六个字段，自包含的成本极低，顺带验证"自包含 repair"这个方向本身有效。
     */
    static final String ROUTE_REPAIR_PROMPT = """
            你上一次的输出不是合法 JSON。重新输出，**只能输出一个 JSON 对象**，直接以 { 开始、以 } 结束。

            字段：
            - candidateDomains：字符串数组，取值只能来自 CHAT / ARTICLE / ARTICLE_SEARCH / NAVIGATE /
              CREATE_ARTICLE / OPTIMIZE_ARTICLE / LEARNING / EDITOR / WALLET / PROFILE
            - requestKind："TASK" 或 "CHAT"
            - confidence：0~1 的小数
            - needsThinking：true 或 false
            - needsThinkingReason：字符串或 null
            - reason：简短理由

            拿不准时给安全值（candidateDomains 给 ["CHAT"]）。禁止解释、注释、markdown。
            """;

    /** 路由段的输出（不是 AiIntent——它没有 candidateDomains 字段，这是新协议的一部分） */
    record RouteResult(List<String> candidateDomains, String requestKind, Double confidence,
                       Boolean needsThinking, String needsThinkingReason, String reason) {
    }

    /**
     * 一次完整分类（B 自己的两段）。
     *
     * <p>返回 {@code null} 表示路由段连域都判不出来——交给上层按"分类失败"降级处理，
     * 不在这里编一个域出来（下游会用错误的域装错误的规则，比降级更糟）。
     */
    static AiIntent classify(ClassifierCandidateRunner runner, String message, PageContextDTO pageContext,
                             List<LearningPlans> plans, TokenUsageAccumulator usage, ClassifierProbe probe) {
        RouteResult route = route(runner, message, pageContext, usage, probe);
        if (route == null) {
            return null;
        }

        List<String> domains = route.candidateDomains().stream()
                .filter(Objects::nonNull)
                .map(domain -> domain.trim().toUpperCase(Locale.ROOT))
                .toList();

        // 纯闲聊：路由段自己就是结论，**不再发起第二次调用** —— 这正是 B 对 A 的结构性优势
        if (domains.isEmpty() || domains.stream().allMatch(DOMAIN_CHAT::equals)) {
            return chatFromRoute(route);
        }

        String extractorPrompt = extractorPrompt(domains);
        return runner.callOnce(repair -> repair
                        ? AiIntentClassifierImpl.buildSystemPrompt(true)
                        : extractorPrompt,
                message, pageContext, plans, usage, probe, ClassifierProbe.CallRole.EXTRACTOR);
    }

    private static RouteResult route(ClassifierCandidateRunner runner, String message, PageContextDTO pageContext,
                                     TokenUsageAccumulator usage, ClassifierProbe probe) {
        if (probe != null) {
            probe.recordRole(ClassifierProbe.CallRole.ROUTE);
        }
        // 路由段**不带计划列表**：分诊只判域，计划清单是提取阶段才需要的东西
        String json = runner.call(ROUTE_PROMPT, message, pageContext, List.of(), usage);
        if (probe != null && json != null) {
            probe.recordResponseChars(json.length());
        }
        RouteResult parsed = runner.parse(json, RouteResult.class);
        if (parsed != null && parsed.candidateDomains() != null) {
            return parsed;
        }

        if (probe != null) {
            probe.recordRole(ClassifierProbe.CallRole.REPAIR);
        }
        String repaired = runner.call(ROUTE_REPAIR_PROMPT, message, pageContext, List.of(), usage);
        if (probe != null && repaired != null) {
            probe.recordResponseChars(repaired.length());
        }
        RouteResult reparsed = runner.parse(repaired, RouteResult.class);
        if (reparsed == null || reparsed.candidateDomains() == null) {
            if (probe != null) {
                probe.markOutcome(ClassifierProbe.Outcome.PARSE_FAILED,
                        "路由段首轮与 repair 均未给出 candidateDomains");
            }
            return null;
        }
        return reparsed;
    }

    /** 纯闲聊的落点：路由段已经给出了结论，把它翻译成生产方式的结果 */
    private static AiIntent chatFromRoute(RouteResult route) {
        AiIntent intent = new AiIntent();
        intent.setIntent("GENERAL_CHAT");
        intent.setConfidence(route.confidence());
        intent.setSuggestedAction("CHAT");
        intent.setSuggestedWorkflowType(null);
        intent.setRisk("LOW");
        intent.setNeedsThinking(route.needsThinking() != null && route.needsThinking());
        intent.setNeedsThinkingReason(route.needsThinkingReason());
        intent.setReason(route.reason() == null ? "路由分诊判为纯闲聊" : route.reason());
        return intent;
    }

    private static List<String> blocksOf(List<String> domains) {
        LinkedHashSet<String> blocks = new LinkedHashSet<>();
        for (String domain : domains) {
            blocks.addAll(BLOCKS_BY_DOMAIN.getOrDefault(domain, List.of()));
        }
        return List.copyOf(blocks);
    }
}
