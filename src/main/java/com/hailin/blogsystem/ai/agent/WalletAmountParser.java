package com.hailin.blogsystem.ai.agent;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从用户原话里解析充值金额。
 *
 * <p><b>为什么不让 LLM 提金额</b>：金额是<b>事实</b>，不是语义。这个项目一贯的判据是
 * 「LLM 判语义、后端判事实」——{@code articleId} 只从页面上下文取、
 * {@code learningPlanId} 由后端映射不从 LLM 读，同理。让金额过一遍 LLM，
 * 用户说「帮我充点钱」时模型可能回一个凭空编的数字，卡片就弹出用户从没说过的金额。
 *
 * <p>解析结果只有三态，而且<b>失败的方向永远是「问用户」，不是「猜」</b>：
 * 抠不到就问，抠出多个不同的也问。宁可多问一句，也不能弹出一张用户没想过的卡。
 */
public final class WalletAmountParser {

    /** 阿拉伯数字：不强制带单位——「帮我充 10」是很自然的说法 */
    private static final Pattern ARABIC = Pattern.compile("(\\d{1,7})");

    /**
     * 中文数字：<b>必须紧跟单位</b>。
     *
     * <p>不要求单位的话，「我一点也不困」里的「一」会被算成 1 元。而「帮我充十」这种
     * 没带单位的说法落到 {@link Kind#NONE} 去追问，方向是安全的——用户补一句「十块」就行。
     */
    private static final Pattern CHINESE = Pattern.compile(
            "([一二两三四五六七八九十百千]+)\\s*(?:元|块钱|块|人民币)");

    /** 「两」和「二」都是 2，两个都要收 */
    private static final Map<Character, Long> DIGITS = Map.of(
            '一', 1L, '二', 2L, '两', 2L, '三', 3L, '四', 4L, '五', 5L,
            '六', 6L, '七', 7L, '八', 8L, '九', 9L);

    private static final Map<Character, Long> UNITS = Map.of(
            '十', 10L, '百', 100L, '千', 1000L);

    private WalletAmountParser() {
    }

    public enum Kind {
        /** 原话里没有金额 */
        NONE,
        /** 有多个**不同**的金额，无法确定用哪个 */
        AMBIGUOUS,
        /** 正好一个 */
        SINGLE
    }

    public record Result(Kind kind, long yuan) {
    }

    public static Result parse(String text) {
        if (text == null || text.isBlank()) {
            return new Result(Kind.NONE, 0L);
        }

        // 用 Set 去重：同一句话里「充 10 块…… 就是 10 块」只算一个金额，
        // 而「充 10 块，另外我昨天花了 500」是两个 → 歧义 → 追问
        Set<Long> found = new LinkedHashSet<>();

        Matcher arabic = ARABIC.matcher(text);
        while (arabic.find()) {
            found.add(Long.parseLong(arabic.group(1)));
        }

        Matcher chinese = CHINESE.matcher(text);
        while (chinese.find()) {
            found.add(chineseToNumber(chinese.group(1)));
        }

        if (found.isEmpty()) {
            return new Result(Kind.NONE, 0L);
        }
        if (found.size() > 1) {
            return new Result(Kind.AMBIGUOUS, 0L);
        }
        return new Result(Kind.SINGLE, found.iterator().next());
    }

    /**
     * 中文数字转阿拉伯（够用到 9999；充值区间只到 1000）。
     *
     * <p>「十」开头的要当 10 而不是 0——{@code current == 0} 时补 1，
     * 这样「十五」= 15、「二十」= 20 都对。
     */
    private static long chineseToNumber(String text) {
        long total = 0L;
        long current = 0L;
        for (char c : text.toCharArray()) {
            Long digit = DIGITS.get(c);
            if (digit != null) {
                current = digit;
                continue;
            }
            Long unit = UNITS.get(c);
            if (unit == null) {
                continue;
            }
            total += (current == 0 ? 1 : current) * unit;
            current = 0;
        }
        return total + current;
    }
}
