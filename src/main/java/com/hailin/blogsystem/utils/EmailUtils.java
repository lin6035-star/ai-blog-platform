package com.hailin.blogsystem.utils;

import java.util.Locale;

/**
 * 邮箱归一化。
 *
 * <p><b>为什么必须只有一处实现</b>：邮箱在两个地方被当作"账号标识"用——
 * 注册时去重、GitHub 登录时匹配已有账号。如果两边归一化规则不一致，
 * 同一个人会得到两个账号：注册时存的是 {@code A@x.com}，GitHub 拿回来的是
 * {@code a@x.com}，匹配不上 → 建新号。**而且这个 bug 只在特定大小写组合下出现**，
 * 测试很容易漏过去。
 *
 * <p>规则：trim + 小写。与 {@code users} 表 {@code utf8mb4_unicode_ci} 的比对语义对齐，
 * 也与 {@code LoginAttemptLimiter} 对用户名的归一化同一个思路。
 *
 * <p>刻意**不做**更激进的规范化（去点号、去 plus 后缀等）：Gmail 的
 * {@code a.b@gmail.com} 与 {@code ab@gmail.com} 确实是同一个信箱，但那是
 * Gmail 独有的规则，套到所有邮箱上会误伤——别的服务商那里这是两个不同地址。
 */
public final class EmailUtils {

    private EmailUtils() {
    }

    /** trim + 小写；null / 空白输入原样返回 null */
    public static String normalize(String email) {
        if (email == null) {
            return null;
        }
        String trimmed = email.trim();
        return trimmed.isEmpty() ? null : trimmed.toLowerCase(Locale.ROOT);
    }
}
