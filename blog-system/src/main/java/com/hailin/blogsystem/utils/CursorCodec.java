package com.hailin.blogsystem.utils;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

/**
 * 分页游标的编解码（游标分页 / keyset 用）。
 *
 * <p><b>为什么编码成不透明 token，而不是直接传 {@code viewCount=87&id=2085...}</b>：
 * 游标是"服务端发出去、客户端原样带回来"的东西，它的内部结构不属于接口契约。
 * 编成 Base64 之后，将来换排序键、加一段、甚至换成 ES 的 sort 值，**前端一个字都不用改**。
 * 明文参数则会把内部结构冻结成契约，改起来要动前后端两侧。
 *
 * <p>⚠️ <b>它不是安全边界</b>：Base64 不是加密，客户端照样能自己造一个。
 * 它防的是"格式非法导致 500"，不是"越权"——游标最多让调用方定位到某一页，
 * 而那一页本来也是公开数据。所以别往游标里塞任何不该给用户看的东西。
 *
 * <p><b>解析失败一律抛 {@link IllegalArgumentException}</b>：由 {@code GlobalExceptionHandler}
 * 统一转成 HTTP 200 + code 40001。**不要在这里返回 null 当"没传游标"处理**——
 * 那会让"客户端 bug / 用户手改 URL"静默退化成"回到第一页"，问题永远暴露不出来。
 *
 * <p>本文件是为了<b>被复用</b>而放在 utils 的：文章列表是第一个用它的地方，
 * 评论列表、公开主页列表要上游标时直接调，不要各写一份。
 */
public final class CursorCodec {

    /** 段分隔符。各段必须是不含它的纯数字串（游标里的值都是 id / 计数 / epoch 毫秒 / ES score）。 */
    private static final String SEPARATOR = "|";

    private static final String INVALID_MESSAGE = "分页游标格式非法";

    private CursorCodec() {
    }

    /**
     * 编码。各段顺序由调用方约定（例如文章列表是 {@code 排序键, id}）。
     *
     * @param parts 至少两段；段内不允许出现 {@value #SEPARATOR}
     */
    public static String encode(String... parts) {
        if (parts == null || parts.length < 2) {
            throw new IllegalArgumentException("游标至少需要两段");
        }
        String raw = String.join(SEPARATOR, parts);
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 解码。段数与内容由调用方自行校验（本方法只管"能不能拆开"）。
     *
     * @throws IllegalArgumentException 不是合法 Base64，或拆不出至少两段
     */
    public static List<String> decode(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            throw new IllegalArgumentException(INVALID_MESSAGE);
        }
        String raw;
        try {
            raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(INVALID_MESSAGE);
        }
        if (raw.isBlank() || raw.startsWith(SEPARATOR) || raw.endsWith(SEPARATOR)
                || raw.contains(SEPARATOR + SEPARATOR)) {
            throw new IllegalArgumentException(INVALID_MESSAGE);
        }
        List<String> parts = List.of(raw.split("\\" + SEPARATOR, -1));
        if (parts.size() < 2) {
            throw new IllegalArgumentException(INVALID_MESSAGE);
        }
        return parts;
    }

    /**
     * 把一段读成长整数。
     *
     * <p>存在的意义是**把异常类型统一**：调用方拿到游标里的"id"时不必再写一遍 try-catch，
     * 也不会因为漏catch 让 {@code NumberFormatException} 冒到兜底 handler 变成 500。
     */
    public static long toLong(String part) {
        try {
            return Long.parseLong(part);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(INVALID_MESSAGE);
        }
    }

    /** 同 {@link #toLong}，给需要小数段的调用方（ES 的 {@code _score} 段）。 */
    public static double toDouble(String part) {
        try {
            return Double.parseDouble(part);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(INVALID_MESSAGE);
        }
    }
}
