package com.hailin.blogsystem.utils;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;

/**
 * 密码强度策略。
 *
 * <p><b>为什么必须做</b>：登录侧的限流挡的是"同一个账号被反复试"，但挡不住
 * <b>撞库</b>——攻击者用泄露的密码库，每个账号只试一两次。这时候账号能不能守住，
 * 完全取决于密码本身够不够强。允许用户设 {@code 1} 的话，前面所有防护都白搭。
 *
 * <p><b>取舍</b>：只拦「长度 < 8」「在常见弱密码表里」「超过 BCrypt 的 72 字节上限」，
 * <b>不引入大小写/数字/符号的组合要求</b>——那些规则（NIST SP 800-63B 已明确不建议）
 * 只会把用户逼向 {@code Password1!} 这种既难记又照样能撞开的东西。
 */
public final class PasswordPolicy {

    /** 最短长度。8 是 NIST SP 800-63B 给出的下限建议 */
    public static final int MIN_LENGTH = 8;

    /**
     * BCrypt 的处理上限：**72 字节，不是 72 字符**。
     *
     * <p>UTF-8 下一个中文字是 3 字节，所以 24 个中文就到顶了。
     * 这一条特别容易在"密码最长 64 个字符"这种看似宽松的规则下被漏掉——
     * 实测 Spring Security 6 的 {@code BCryptPasswordEncoder} 对超长密码**抛异常**
     * （{@code password cannot be more than 72 bytes}），不是静默截断，
     * 所以用户会看到 500「网站坏了」。
     */
    public static final int MAX_BCRYPT_BYTES = 72;

    /**
     * 常见弱密码。
     *
     * <p>**刻意只收一小批**：完整弱密码字典有几百万条，内置不现实，也不可能靠它"穷尽"。
     * 这里挑的是撞库字典里出现频率最高的那些——它们覆盖了实际成功撞库的绝大部分。
     * 真要更强，正确做法是接一个泄露库比对服务，而不是往这个集合里堆词。
     *
     * <p>比对前会统一转小写，所以这里只存小写形式。
     */
    private static final Set<String> WEAK_PASSWORDS = Set.of(
            "12345678", "123456789", "1234567890", "87654321", "11111111", "00000000",
            "88888888", "66666666", "abc12345", "abcd1234", "a1234567", "aa123456",
            "password", "password1", "password123", "passw0rd", "p@ssw0rd",
            "qwerty123", "qwertyuiop", "qwe12345", "1qaz2wsx", "1q2w3e4r", "zaq12wsx",
            "asdfghjk", "asdf1234", "iloveyou", "admin123", "administrator",
            "letmein1", "welcome1", "monkey123", "dragon123", "football",
            "sunshine", "princess", "superman", "trustno1", "shadow123"
    );

    private PasswordPolicy() {
    }

    /**
     * 是否通过强度检查。
     *
     * @return {@code null} 表示通过；否则返回**给用户看的中文原因**
     */
    public static String validate(String password) {
        if (password == null || password.length() < MIN_LENGTH) {
            return "密码至少 " + MIN_LENGTH + " 个字符";
        }

        // 长度上限按**字节**算：@Size 数的是字符，而 BCrypt 只处理前 72 字节。
        // 不拦的话，用户设 64 个中文字就会撞上 BCrypt 的异常 → 500
        if (exceedsBcryptLimit(password)) {
            return "密码太长了（超过 " + MAX_BCRYPT_BYTES + " 字节，中文大约 24 个字）";
        }

        // 统一转小写再比：Password / PASSWORD 和 password 一样弱，
        // 但**不做 trim 之类的归一化**——密码里的空格是用户有意输入的
        if (WEAK_PASSWORDS.contains(password.toLowerCase(Locale.ROOT))) {
            return "这个密码太常见了，换一个吧";
        }

        return null;
    }

    /**
     * 是否超过 BCrypt 的 72 字节上限。
     *
     * <p>登录路径也要用它：正常注册的密码不可能超长（{@link #validate} 拦了），
     * 但**攻击者可以随便构造一个超长密码**发过来——不拦的话每次请求都换来一个 500。
     */
    public static boolean exceedsBcryptLimit(String password) {
        return password != null
                && password.getBytes(StandardCharsets.UTF_8).length > MAX_BCRYPT_BYTES;
    }
}
