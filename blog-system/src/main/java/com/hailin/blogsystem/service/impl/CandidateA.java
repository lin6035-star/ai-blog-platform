package com.hailin.blogsystem.service.impl;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * P3 候选 A：**保守的单调用按需装载**（2026-10-01）。
 *
 * <p><b>它不是什么</b>：不是"按 pageContext 删掉其他域"。设计稿 §16.4 早就写明——
 * {@code pageContext} 是后端确定性线索，但**不是域权限边界**：助手是全站入口，
 * 用户在文章详情页仍然可以问学习计划、钱包或创建 Workflow。按页面删整域会直接造成功能退化。
 *
 * <p><b>它是什么</b>：三层装载 + fail-open
 * <ol>
 *   <li><b>公共块</b>：任何请求都装（字段契约、建议字段规则、输出格式……）；</li>
 *   <li><b>页面专属块</b>：{@code pageType} 直接决定的（编辑器页装编辑器块）；</li>
 *   <li><b>原文线索块</b>：用户原话里出现高召回线索才装（"学习计划"→学习块、"充值"→钱包块）；</li>
 *   <li><b>兜底</b>：线索不明确 / 命中过多 / 消息过长 → **拼回 FULL**，绝不为了省 Token 降低召回。</li>
 * </ol>
 *
 * <p><b>为什么线索表只做"高召回提示"</b>：一旦它开始追求精确，就等于把手写关键词分类器
 * 又造了一遍（还会和 LLM 判据漂移）。所以这里只认**明确无歧义**的词，
 * 认不出来就 FULL——宁可多花 Token，不可误判。
 */
final class CandidateA {

    private CandidateA() {
    }

    /** 装载结果：prompt + 装了哪些块 + 是否走了 FULL 兜底 */
    record Assembled(String prompt, List<String> includedBlocks, boolean fullFallback) {

        int chars() {
            return prompt.length();
        }
    }

    /** 任何请求都要装的块——**字段契约在内，单调用下省不掉它** */
    private static final List<String> COMMON = List.of(
            "HEAD_INTENT_LIST", "GENERAL_AND_PROFILE", "SUGGEST_FIELDS", "NEEDS_THINKING",
            "RISK", "CONFIDENCE_MISC", "PROJECT_CONSULT", "EXAMPLES_AND_SCHEMA", "OUTPUT_FORMAT");

    /** 页面专属：只有这个页面类型才装 */
    private static final Map<String, List<String>> BY_PAGE = Map.of(
            "editor-new", List.of("EDITOR", "PAGE_ACTION_RULES"),
            "editor-edit", List.of("EDITOR", "PAGE_ACTION_RULES"));

    private static final List<String> LEARNING_BLOCKS = List.of("LEARNING_PLAN", "LEARNING_AGENT");
    private static final List<String> ARTICLE_WRITE_BLOCKS = List.of("ARTICLE_CONTENT");
    private static final List<String> OPTIMIZE_BLOCKS = List.of("ARTICLE_OPTIMIZE");
    private static final List<String> CREATE_BLOCKS = List.of("CREATE_ARTICLE");
    private static final List<String> NAVIGATE_BLOCKS = List.of("NAVIGATE");
    private static final List<String> SEARCH_BLOCKS = List.of("SEARCH");
    private static final List<String> EDITOR_BLOCKS = List.of("EDITOR", "PAGE_ACTION_RULES");
    private static final List<String> WALLET_BLOCKS = List.of("WALLET");

    /**
     * 高召回线索 → 块。
     *
     * <p>刻意**少而明确**：这里每加一个模糊词，就多一分误判风险；而误判的代价是"该装的规则没装"。
     *
     * <p><b>2026-10-01 第二轮补全</b>：第一版只覆盖了最"显眼"的说法，结果 10/26 条样例一条线索
     * 都没命中而退回 FULL——"帮我充10块钱"没命中「充值/充钱」、「帮我写一篇…的文章」没命中
     * 「写一篇文章」。补的时候**按域补**（把每个域用户可能用的几种说法凑齐），不按样例补：
     * 只对着 26 条 spike 加词，等于把线索表过拟合到样例集上，换一批说法立刻退化。
     *
     * <p>词表分两类，风险不同：
     * <ul>
     *   <li><b>窄词</b>（多字、几乎无歧义）："充值""隐藏""点赞"——命中即装对应块，安全；</li>
     *   <li><b>宽词</b>（单字或常见词）："充""拆""公开""写一篇"——会误命中（"充分""公开课"），
     *       但误命中的代价只是**多装一个块**（多花 Token，不丢规则）；真正的坏情况是漏装，
     *       所以这里宁可宽。</li>
     * </ul>
     */
    private static final Map<String, List<String>> BY_CLUE = new LinkedHashMap<>();

    static {
        // —— 学习域（计划 / 阶段 / 攻坚）——
        BY_CLUE.put("学习", LEARNING_BLOCKS);
        BY_CLUE.put("计划", LEARNING_BLOCKS);
        BY_CLUE.put("规划", LEARNING_BLOCKS);
        BY_CLUE.put("难点", LEARNING_BLOCKS);
        BY_CLUE.put("拆解", LEARNING_BLOCKS);
        BY_CLUE.put("拆", LEARNING_BLOCKS);
        BY_CLUE.put("不懂", LEARNING_BLOCKS);
        BY_CLUE.put("练习", LEARNING_BLOCKS);

        // —— 文章受控写（点赞 / 收藏 / 关注 / 评论 / 隐藏 / 公开）——
        BY_CLUE.put("点赞", ARTICLE_WRITE_BLOCKS);
        BY_CLUE.put("收藏", ARTICLE_WRITE_BLOCKS);
        BY_CLUE.put("关注", ARTICLE_WRITE_BLOCKS);
        BY_CLUE.put("评论", ARTICLE_WRITE_BLOCKS);
        BY_CLUE.put("隐藏", ARTICLE_WRITE_BLOCKS);
        BY_CLUE.put("公开", ARTICLE_WRITE_BLOCKS);

        // —— 文章优化 Workflow ——
        BY_CLUE.put("优化", OPTIMIZE_BLOCKS);
        BY_CLUE.put("润色", OPTIMIZE_BLOCKS);
        BY_CLUE.put("重写", OPTIMIZE_BLOCKS);
        BY_CLUE.put("改写", OPTIMIZE_BLOCKS);

        // —— 创建文章 Workflow ——
        BY_CLUE.put("写一篇文章", CREATE_BLOCKS);
        BY_CLUE.put("写一篇", CREATE_BLOCKS);
        BY_CLUE.put("写篇", CREATE_BLOCKS);
        BY_CLUE.put("写博客", CREATE_BLOCKS);
        BY_CLUE.put("生成文章", CREATE_BLOCKS);
        BY_CLUE.put("新文章", CREATE_BLOCKS);

        // —— 钱包 ——
        BY_CLUE.put("充值", WALLET_BLOCKS);
        BY_CLUE.put("充钱", WALLET_BLOCKS);
        BY_CLUE.put("充", WALLET_BLOCKS);
        BY_CLUE.put("余额", WALLET_BLOCKS);
        BY_CLUE.put("钱包", WALLET_BLOCKS);

        // —— 导航 ——
        BY_CLUE.put("跳转", NAVIGATE_BLOCKS);
        BY_CLUE.put("主页", NAVIGATE_BLOCKS);

        // —— 站内检索 ——
        BY_CLUE.put("找文章", SEARCH_BLOCKS);
        BY_CLUE.put("找找", SEARCH_BLOCKS);
        BY_CLUE.put("还有没有", SEARCH_BLOCKS);
        BY_CLUE.put("相关文章", SEARCH_BLOCKS);
        BY_CLUE.put("搜", SEARCH_BLOCKS);
        BY_CLUE.put("检索", SEARCH_BLOCKS);

        // —— 编辑器页面动作 ——
        BY_CLUE.put("草稿", EDITOR_BLOCKS);
        BY_CLUE.put("发布", EDITOR_BLOCKS);
    }

    /**
     * 装载块数上限：超过它说明这句话牵扯太多域，按需装载省不了多少，直接 FULL 更安全。
     *
     * <p>按**块**计数而不是按命中词计数（第一版是后者）："找找还有没有相关文章"会一次命中
     * 三个同域线索，按词计数会把"一句话里的同域强调"误判成"跨了很多域"而退回 FULL。
     */
    private static final int MAX_BLOCKS = 4;

    /**
     * 复合目标信号：句子里出现这些连接词，说明**一句话里可能有两个目标**。
     *
     * <p><b>为什么必须直接 FULL</b>：线索表命中的永远是"被写进词表的那个目标"，第二个目标完全可能不在表里。
     * 真实漏洞长这样 —— "点赞这篇文章，再给我弄个复习路线"：
     * {@code 点赞} 命中文章块，而"复习路线"不在学习词表（表里只有 学习/计划/规划/难点/拆解/拆/不懂/练习），
     * 于是学习规则被裁掉，**第二个目标静默丢失**。
     *
     * <p>多目标编排是第三刀/第四刀刚做完的能力，也正是最贵的那部分（丢一个目标 = 一次编排失败），
     * 不能为了省 Token 拿它冒险。所以这里**刻意偏向保守**：宁可让"再优化一下"这类单目标句子也退回 FULL，
     * 也不赌第二个目标恰好也在词表里。
     *
     * <p>代价是诚实的：26 条 spike 里 6 条复合样例会退回 FULL，离线总降幅从 33.4% 降到约 24%
     * ——仍然远超 15% 的工程门槛，而简单请求（闲聊、单域动作、编辑器、钱包）的收益一个不少。
     */
    private static final List<String> COMPOUND_SIGNALS = List.of(
            "再", "然后", "同时", "顺便", "接着", "以及", "并且", "之后");

    /**
     * FULL 兜底：不按需装载，**直接复用生产那 19 块的全量拼装**。
     *
     * <p>不再自己拼一遍：自己拼会在末尾多出一个换行（{@code join("\n")} 与"每块各加一个 \n"的差别，
     * 实测 25626 vs 25627），而"回退 FULL"必须是**同一个 prompt**——
     * 差一个字符就等于回退到了第三个东西，一键回退的语义和测量口径都会跟着失真。
     */
    static Assembled full() {
        return new Assembled(AiIntentClassifierImpl.buildSystemPrompt(false), ALL_BLOCKS, true);
    }

    /**
     * 按需装载。
     *
     * @param message  用户原话
     * @param pageType 页面类型（可为 null）
     */
    static Assembled assemble(String message, String pageType) {
        String text = message == null ? "" : message;

        // 复合目标信号：可能有两个目标就一定 FULL——线索表只认识"它认识的那个目标"
        for (String signal : COMPOUND_SIGNALS) {
            if (text.contains(signal)) {
                return full();
            }
        }

        Set<String> hit = new LinkedHashSet<>();
        for (Map.Entry<String, List<String>> entry : BY_CLUE.entrySet()) {
            if (text.contains(entry.getKey())) {
                hit.addAll(entry.getValue());
            }
        }
        // 一条线索都没命中：可能是闲聊，也可能是没写进线索表的新说法 → 不赌，FULL
        if (hit.isEmpty()) {
            return full();
        }
        // 牵扯的块太多：按需装载省不下多少，而漏装的风险却是实打实的
        if (hit.size() > MAX_BLOCKS) {
            return full();
        }

        if (pageType != null) {
            hit.addAll(BY_PAGE.getOrDefault(pageType, List.of()));
        }

        List<String> includes = includeOrder(hit);
        return new Assembled(assembleBlocks(includes), includes, false);
    }

    /**
     * 公共块 + 指定块，按统一顺序拼成 prompt。
     *
     * <p>包私有，给**候选 B 的 Extractor 段**用：它要装的是"同一份字段契约 + 另一套选块依据
     * （模型路由而不是关键词线索）"。两处各写一份拼装必然漂移，而
     * {@code EXAMPLES_AND_SCHEMA}（字段契约，占整个 prompt 的 27%）正是最不能漂的东西。
     */
    static String assembleWithCommon(Collection<String> extraBlocks) {
        return assembleBlocks(includeOrder(extraBlocks));
    }

    /** 装载顺序：公共块在前，其余按 FULL 的块顺序——同一组块拼出的 prompt 才能逐字一致 */
    private static List<String> includeOrder(Collection<String> blocks) {
        List<String> includes = new ArrayList<>(COMMON);
        for (String block : ALL_BLOCKS) {
            if (blocks.contains(block) && !includes.contains(block)) {
                includes.add(block);
            }
        }
        return includes;
    }

    private static String assembleBlocks(List<String> includes) {
        StringBuilder sb = new StringBuilder();
        for (String block : includes) {
            sb.append(textOf(block)).append('\n');
        }
        return sb.toString();
    }

    private static final List<String> ALL_BLOCKS = List.of(
            "HEAD_INTENT_LIST", "GENERAL_AND_PROFILE", "ARTICLE_CONTENT", "NAVIGATE", "SEARCH",
            "CREATE_ARTICLE", "LEARNING_AGENT", "SUGGEST_FIELDS", "NEEDS_THINKING", "RISK",
            "LEARNING_PLAN", "CONFIDENCE_MISC", "ARTICLE_OPTIMIZE", "PROJECT_CONSULT",
            "EXAMPLES_AND_SCHEMA", "PAGE_ACTION_RULES", "EDITOR", "WALLET", "OUTPUT_FORMAT");

    private static String textOf(String name) {
        return switch (name) {
            case "HEAD_INTENT_LIST" -> ClassifierPromptBlocks.HEAD_INTENT_LIST;
            case "GENERAL_AND_PROFILE" -> ClassifierPromptBlocks.GENERAL_AND_PROFILE;
            case "ARTICLE_CONTENT" -> ClassifierPromptBlocks.ARTICLE_CONTENT;
            case "NAVIGATE" -> ClassifierPromptBlocks.NAVIGATE;
            case "SEARCH" -> ClassifierPromptBlocks.SEARCH;
            case "CREATE_ARTICLE" -> ClassifierPromptBlocks.CREATE_ARTICLE;
            case "LEARNING_AGENT" -> ClassifierPromptBlocks.LEARNING_AGENT;
            case "SUGGEST_FIELDS" -> ClassifierPromptBlocks.SUGGEST_FIELDS;
            case "NEEDS_THINKING" -> ClassifierPromptBlocks.NEEDS_THINKING;
            case "RISK" -> ClassifierPromptBlocks.RISK;
            case "LEARNING_PLAN" -> ClassifierPromptBlocks.LEARNING_PLAN;
            case "CONFIDENCE_MISC" -> ClassifierPromptBlocks.CONFIDENCE_MISC;
            case "ARTICLE_OPTIMIZE" -> ClassifierPromptBlocks.ARTICLE_OPTIMIZE;
            case "PROJECT_CONSULT" -> ClassifierPromptBlocks.PROJECT_CONSULT;
            case "EXAMPLES_AND_SCHEMA" -> ClassifierPromptBlocks.EXAMPLES_AND_SCHEMA;
            case "PAGE_ACTION_RULES" -> ClassifierPromptBlocks.PAGE_ACTION_RULES;
            case "EDITOR" -> ClassifierPromptBlocks.EDITOR;
            case "WALLET" -> ClassifierPromptBlocks.WALLET;
            case "OUTPUT_FORMAT" -> ClassifierPromptBlocks.OUTPUT_FORMAT;
            default -> throw new IllegalArgumentException("未知块：" + name);
        };
    }
}
