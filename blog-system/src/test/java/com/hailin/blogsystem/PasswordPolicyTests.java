package com.hailin.blogsystem;

import com.hailin.blogsystem.utils.PasswordPolicy;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 密码强度策略。
 *
 * <p>这层要挡的不是"同一个账号被反复试"（那是限流的事），而是**撞库**：
 * 攻击者拿泄露的密码库，每个账号只试一两次。限流几乎看不见它，只有密码本身的强度能挡。
 */
class PasswordPolicyTests {

    @Test
    void acceptsAReasonablePassword() {
        assertThat(PasswordPolicy.validate("mySecret2026")).isNull();
    }

    /** 长度是硬门槛：7 位一律拒 */
    @Test
    void rejectsTooShortPassword() {
        assertThat(PasswordPolicy.validate("abc1234")).isEqualTo("密码至少 8 个字符");
        assertThat(PasswordPolicy.validate("")).isEqualTo("密码至少 8 个字符");
        assertThat(PasswordPolicy.validate(null)).isEqualTo("密码至少 8 个字符");
    }

    /** 刚好 8 位且不在弱密码表里 → 通过（边界值明确） */
    @Test
    void acceptsExactlyMinLengthPassword() {
        assertThat(PasswordPolicy.validate("wxyz1234")).isNull();
    }

    @Test
    void rejectsCommonWeakPassword() {
        assertThat(PasswordPolicy.validate("password123")).isEqualTo("这个密码太常见了，换一个吧");
        assertThat(PasswordPolicy.validate("12345678")).isEqualTo("这个密码太常见了，换一个吧");
    }

    /**
     * 大小写不改变强弱：{@code Password123} 和其他变体一样容易撞开。
     *
     * <p>这条容易漏——只存小写形式、比对时忘了转小写的话，
     * 攻击者把字典里的词首字母大写就能全部绕过。
     */
    @Test
    void weakCheckIsCaseInsensitive() {
        assertThat(PasswordPolicy.validate("PASSWORD123")).isEqualTo("这个密码太常见了，换一个吧");
        assertThat(PasswordPolicy.validate("PassWord123")).isEqualTo("这个密码太常见了，换一个吧");
    }

    /** 密码里的空格是用户有意输入的，不该被 trim 掉（也不该因此被当成"空白"拒掉） */
    @Test
    void doesNotTrimPassword() {
        assertThat(PasswordPolicy.validate("  pass word  ")).isNull();
    }

    /**
     * BCrypt 的上限是 **72 字节**，不是 72 字符 —— 这条特别容易漏。
     *
     * <p>加了 {@code @Size(max = 64)} 看起来"长度已经限制了"，但那是按**字符**算的：
     * 64 个中文 = 192 字节，照样超。而 BCrypt 超限时是**抛异常**（实测 Spring Security 6
     * 的行为，不是静默截断），用户会看到 500。
     */
    @Test
    void rejectsPasswordBeyondBcryptByteLimit() {
        assertThat(PasswordPolicy.validate("密".repeat(24)))
                .as("24 个中文 = 72 字节，正好卡在上限，应当通过")
                .isNull();

        assertThat(PasswordPolicy.validate("密".repeat(25)))
                .isEqualTo("密码太长了（超过 72 字节，中文大约 24 个字）");
    }

    /** 登录路径的前置拦截：超长密码不该打到 BCrypt 上换来一个 500 */
    @Test
    void detectsBcryptLimitForLoginPath() {
        assertThat(PasswordPolicy.exceedsBcryptLimit("密".repeat(24))).isFalse();
        assertThat(PasswordPolicy.exceedsBcryptLimit("密".repeat(25))).isTrue();
        assertThat(PasswordPolicy.exceedsBcryptLimit("testpass123")).isFalse();
        assertThat(PasswordPolicy.exceedsBcryptLimit(null)).isFalse();
    }
}
