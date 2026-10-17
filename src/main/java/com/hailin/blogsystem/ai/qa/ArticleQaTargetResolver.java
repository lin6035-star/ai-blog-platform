package com.hailin.blogsystem.ai.qa;

import com.hailin.blogsystem.ai.agent.ArticleSessionAnchorService;
import com.hailin.blogsystem.entity.Articles;
import com.hailin.blogsystem.entity.dto.PageContextDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * QA 目标决议器（V3.9）：普通 QA 管道没有决策器可输出 anchorMode，指代消解用确定性后端规则。
 *
 * 职责 = 候选决议 + 可读加载合一，单次请求 resolve 一次，结果分发正文注入与来源卡两消费点：
 * - PC 候选：pageContext.articleId 经 loadReadable 加载成功才成立（脏 ID / 他人隐藏 / 已删除 → 无 PC）
 * - AC 候选：resolveReadable（会话归属 + 公开可读或本人全状态，锚里可含他人公开文章）
 * - 措辞信号（闭合词集，只判"消息指哪个方向的候选"，不做文章定位/不比标题）→ 决议矩阵：
 *   强会话近指(刚刚/刚才+文章指代) → 锚优先；强当前页(这篇系) → 当前页优先；
 *   弱指代(那篇)双候选 → 追问态不猜；唯一候选 → 直接用；其余 → 说明态
 * intent.articleId 全程不参与（LLM 输出不可信，V3.8「模型不输出 ID」安全资产延续）。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ArticleQaTargetResolver {

    /** 决议输出：target = 本请求要读的文章（已验证可读，含正文）；promptNote = 追问/说明文案。 */
    public record QaTarget(Articles article, String promptNote) {

        public static QaTarget target(Articles article) {
            return new QaTarget(article, null);
        }

        public static QaTarget promptNote(String note) {
            return new QaTarget(null, note);
        }

        public boolean hasArticle() {
            return article != null;
        }
    }

    /** 措辞信号档位：只回答"消息指哪个方向的候选"。 */
    public enum Signal {
        /** 强当前页：「这篇 / 这篇文章 / 当前文章 / 它」（无时间近指）。 */
        S1_STRONG_PAGE,
        /** 强会话近指：时间近指（刚刚/刚才）∧ 文章指代同现。 */
        S2_STRONG_RECENT,
        /** 弱指代：「那篇」（无时间词，可能是当前页口语也可能是会话里的）。 */
        S3_WEAK,
        /** 无指代词（详情页大多数问句，语境隐含当前页）。 */
        S0_NONE
    }

    /**
     * 跨级指代判定（2026-10-18 补）：用户指向"比最近一次更早"的文章。
     *
     * 与 {@link Signal} **正交**而非并列的一档：Signal 回答"指哪个方向的候选"（页面 / 会话 / 都不清），
     * 而"再前面那篇"同时含跨级词与"那篇"，塞进互斥枚举装不下两个维度。
     *
     * 为什么命中就追问、不静默回落：会话只保留最近一次定位（`last_article_id` 单槽），
     * 序位指代**结构性无解**——静默当成本会话最近那篇就是"答错一篇文章"，
     * 比多问一句严重得多（V3.8/V3.9 一贯的「宁可不猜」）。
     *
     * 词集**刻意收窄**到明确排除最近一个的说法：
     * - 收 `再前面/再往前/更早/上上/倒数第二`
     * - **不收「前一篇/上一篇」**：它完全可能指"上一轮说的那篇"（就是最近锚），
     *   收进来会把正常近指误伤成追问
     * - 跨级词必须**与文章指代同现**：「更早说的那个观点」是对话内容追问，不是指文章
     *   （同 S2 的设计：时间近指也要求与文章指代同现）
     * - 指代词含「篇」是为了覆盖"上上篇/倒数第二篇"这类不带"那篇"的说法
     */
    static boolean isCrossLevel(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        boolean crossLevelWord = containsAny(message, "再前面", "再往前", "更早", "上上", "倒数第二");
        boolean articleDeixis = containsAny(message, "那篇", "这篇", "文章", "它", "篇");
        return crossLevelWord && articleDeixis;
    }

    private final ArticleSessionAnchorService anchorService;

    /**
     * 单请求决议入口。页面/会话候选 + 用户措辞 → 目标文章或提示文案。
     */
    public QaTarget resolve(String message, PageContextDTO pageContext, Long sessionId, Long userId) {
        Articles pageArticle = resolvePageCandidate(pageContext, userId);
        Articles anchorArticle = anchorService.resolveReadable(sessionId, userId);

        // 跨级指代（2026-10-18）：排在 S 档之前——单锚下无解，不猜、直接追问。
        // 不做游客豁免（V3.9 对 S2 有豁免）：游客说"再前面那篇"同样不可能指当前页这篇。
        if (isCrossLevel(message)) {
            return QaTarget.promptNote(noteCrossLevel(
                    pageArticle == null ? null : pageArticle.getTitle(),
                    anchorArticle == null ? null : anchorArticle.getTitle()));
        }

        // 游客无归属会话：没有"会话最近文章"概念，会话近指无从解析 → 信号降级，
        // 避免详情页问"刚才那篇"（大概率是当前页口语）被澄清规则误伤
        Signal signal = sessionId == null ? Signal.S0_NONE : classifySignal(message);
        boolean hasPage = pageArticle != null;
        boolean hasAnchor = anchorArticle != null;

        // 强会话近指：锚优先；锚无 → 说明态（即使是详情页也不许硬套当前页——V3.8 老大实测场景）
        if (signal == Signal.S2_STRONG_RECENT) {
            if (hasAnchor) {
                return QaTarget.target(anchorArticle);
            }
            return QaTarget.promptNote(noteRecentNoRecord(hasPage ? pageArticle.getTitle() : null));
        }

        // 双候选都存在 + 弱指代 → 追问态（带权威标题，不猜）
        if (signal == Signal.S3_WEAK && hasPage && hasAnchor) {
            return QaTarget.promptNote(askWhich(pageArticle.getTitle(), anchorArticle.getTitle()));
        }

        // 其余：页面优先（唯一候选直接使用，弱指代不打断），页面无 → 锚唯一候选，都无 → 说明态
        if (hasPage) {
            return QaTarget.target(pageArticle);
        }
        if (hasAnchor) {
            return QaTarget.target(anchorArticle);
        }
        return QaTarget.promptNote(noteNoArticleAtAll());
    }

    /**
     * 措辞信号分类（词集语义闭合：时间近指表达与文章指代词有限，可评测）。
     * 时间近指必须与文章指代同现才算强会话近指——「刚才说的观点再展开」是对话内容追问，不算。
     */
    static Signal classifySignal(String message) {
        boolean timeRecent = containsAny(message, "刚刚", "刚才");
        boolean articleDeixis = containsAny(message, "那篇", "这篇", "文章", "它");
        if (timeRecent && articleDeixis) {
            return Signal.S2_STRONG_RECENT;
        }
        if (!timeRecent && containsAny(message, "这篇", "当前文章", "它")) {
            return Signal.S1_STRONG_PAGE;
        }
        if (!timeRecent && containsAny(message, "那篇")) {
            return Signal.S3_WEAK;
        }
        return Signal.S0_NONE;
    }

    private static boolean containsAny(String message, String... words) {
        if (message == null || message.isBlank()) {
            return false;
        }
        for (String word : words) {
            if (message.contains(word)) {
                return true;
            }
        }
        return false;
    }

    /**
     * PC 候选：页面 ID 经读权限加载成功才算候选（先加载后成候选，防"决议选 PC、加载却失败"割裂）。
     */
    private Articles resolvePageCandidate(PageContextDTO pageContext, Long userId) {
        if (pageContext == null) {
            return null;
        }
        String articleId = pageContext.getArticleId();
        if (articleId == null || articleId.isBlank()) {
            return null;
        }
        Long id;
        try {
            id = Long.valueOf(articleId.trim());
        } catch (NumberFormatException e) {
            return null;
        }
        return anchorService.loadReadable(id, userId);
    }

    private static String askWhich(String pageTitle, String anchorTitle) {
        return "用户用\"那篇\"指代一篇文章，但当前可能指：当前页《" + safe(pageTitle)
                + "》或本会话最近聊过的《" + safe(anchorTitle)
                + "》。先用一句话向用户确认指哪一篇，确认前不要回答文章内容。";
    }

    /**
     * 跨级指代追问文案（2026-10-18）：如实说明"只记得最近一篇"，引导用户给标题或打开详情页。
     *
     * 刻意**不**把现有候选包装成"二选一"（不像 {@link #askWhich}）——用户要的第三个本来就不在候选里，
     * 让他从两个错的中挑一个只会更糟。候选标题只作"我能看到什么"的如实交代。
     */
    private static String noteCrossLevel(String pageTitle, String anchorTitle) {
        StringBuilder sb = new StringBuilder(
                "用户用\"再前面/更早\"指代一篇比最近一次更早的文章，但本会话只保留最近一次定位的文章，"
                        + "无法按序找回更早的那一篇。");
        if (pageTitle != null && anchorTitle != null) {
            sb.append("系统当前能看到的只有当前页《").append(safe(pageTitle))
                    .append("》与本会话最近聊过的《").append(safe(anchorTitle))
                    .append("》——都不是用户要的\"再前面那篇\"，不要拿它们充当答案。");
        } else if (pageTitle != null) {
            sb.append("系统当前能看到的只有当前页《").append(safe(pageTitle))
                    .append("》——它不是用户要的\"再前面那篇\"，不要拿它充当答案。");
        } else if (anchorTitle != null) {
            sb.append("系统当前能看到的只有本会话最近聊过的《").append(safe(anchorTitle))
                    .append("》——它不是用户要的\"再前面那篇\"，不要拿它充当答案。");
        }
        sb.append("先用一句话如实说明你只记得最近一篇，请用户说出目标文章的标题、"
                + "或打开那篇文章的详情页后再问；在用户给出标题或打开页面之前，不要回答任何文章内容。");
        return sb.toString();
    }

    private static String noteRecentNoRecord(String pageTitle) {
        String note = "用户用\"刚刚/刚才\"指代本会话早前聊过的文章，但本会话没有可读的文章记录";
        if (pageTitle != null) {
            note += "；即使当前页是《" + safe(pageTitle) + "》也不要把这篇当\"刚刚那篇\"";
        }
        return note + "。如实向用户说明无法定位\"刚刚那篇\"，并引导打开目标文章详情页后再问。";
    }

    private static String noteNoArticleAtAll() {
        return "用户询问某篇文章内容，但既没有当前页面文章、本会话也没有最近聊过的文章记录。"
                + "向用户说明情况，并引导打开文章详情页后再问。";
    }

    private static String safe(String title) {
        return title == null ? "" : title;
    }
}
