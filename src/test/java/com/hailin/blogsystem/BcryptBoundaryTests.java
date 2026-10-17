package com.hailin.blogsystem;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * BCrypt 的 **72 字节上限**边界。
 *
 * <p>BCrypt 算法只处理密码的前 72 <b>字节</b>（不是字符）。这条边界容易被"密码最长 64 个字符"
 * 这种看似宽松的规则漏掉：UTF-8 下一个中文字符 3 字节，**64 个中文 = 192 字节**，远超上限。
 *
 * <p>两种失败模式都不可接受：
 * <ul>
 *   <li><b>抛异常</b> → 用户设个中文长密码直接 500，看着像"网站坏了"；</li>
 *   <li><b>静默截断</b> → 前 24 个字符相同的两个不同密码能互相登录，是实打实的认证绕过。</li>
 * </ul>
 *
 * <p>写这条用例是为了<b>确认当前版本的行为</b>，而不是假设它安全。
 */
class BcryptBoundaryTests {

    private final PasswordEncoder encoder = new BCryptPasswordEncoder();

    @Test
    void shortAsciiPasswordWorks() {
        String hash = encoder.encode("testpass123");
        assertThat(hash).hasSize(60);          // BCrypt 固定 60 字符
        assertThat(hash).startsWith("$2a$10$"); // 算法版本 + cost=10
        assertThat(encoder.matches("testpass123", hash)).isTrue();
    }

    /** 同一密码两次哈希结果不同 → 证明每次都用新随机盐（彩虹表/批量碰撞的前提被破坏） */
    @Test
    void samePasswordProducesDifferentHashes() {
        String a = encoder.encode("testpass123");
        String b = encoder.encode("testpass123");
        assertThat(a).isNotEqualTo(b);
        assertThat(encoder.matches("testpass123", a)).isTrue();
        assertThat(encoder.matches("testpass123", b)).isTrue();
    }

    /** 刚好 72 字节（24 个中文）应当正常 */
    @Test
    void exactly72BytesWorks() {
        String password = "密".repeat(24);      // 24 × 3 = 72 字节
        assertThatCode(() -> encoder.encode(password)).doesNotThrowAnyException();
    }

    /**
     * 超过 72 字节（64 个中文字符 = 192 字节）——**这里记录当前实际行为**。
     *
     * <p>如果它抛异常：说明 DTO 的 `@Size(max=64)` 是按**字符**算的，挡不住按**字节**才算得准的 BCrypt 上限，
     * 用户设中文长密码会得到 500。
     *
     * <p>如果它静默截断：`"密"×24` 与 `"密"×64` 应能互相 matches —— 那就是认证绕过，必须拦。
     */
    @Test
    void passwordBeyond72BytesBehaviorIsKnown() {
        String longPassword = "密".repeat(64);   // 192 字节
        String prefix = "密".repeat(24);         // 72 字节，正好卡在上限

        try {
            String hash = encoder.encode(longPassword);
            // 没抛异常 = 发生了截断。此时前 72 字节相同的密码必须被认为是同一个 —— 这就是风险
            boolean crossMatches = encoder.matches(prefix, hash);
            System.out.println("[BCRYPT-72] 当前行为 = 静默截断，跨密码 matches=" + crossMatches);
            assertThat(crossMatches)
                    .as("BCrypt 静默截断时，前 72 字节相同的不同密码会互相通过校验（认证绕过）")
                    .isTrue();
        } catch (IllegalArgumentException e) {
            // 抛异常 = 这个版本会拒绝超长密码。记录在案：接口层需要给出可读提示，
            // 而不是让它变成 500
            System.out.println("[BCRYPT-72] 当前行为 = 拒绝超长密码：" + e.getMessage());
            assertThat(e.getMessage()).containsIgnoringCase("72");
        }
    }
}
