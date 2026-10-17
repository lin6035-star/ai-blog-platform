package com.hailin.blogsystem;

import com.hailin.blogsystem.utils.EmailUtils;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 邮箱归一化。
 *
 * <p>它看着微不足道，但**必须只有一处实现**：注册去重和 GitHub 登录匹配都依赖它。
 * 两边规则一旦不一致，同一个人就会得到两个账号——而且只在特定大小写组合下出现，
 * 手工测试很容易漏过去。
 */
class EmailUtilsTests {

    @Test
    void trimsAndLowercases() {
        assertThat(EmailUtils.normalize("  Foo.Bar@Example.COM  ")).isEqualTo("foo.bar@example.com");
    }

    @Test
    void nullStaysNull() {
        assertThat(EmailUtils.normalize(null)).isNull();
    }

    /**
     * 空白必须变成 null 而不是空串。
     *
     * <p>空串会被拿去匹配 email 为空的账号（老数据全是 NULL，但只要有一个空串就会命中），
     * 把 GitHub 身份绑到毫不相干的人身上。
     */
    @Test
    void blankBecomesNull() {
        assertThat(EmailUtils.normalize("")).isNull();
        assertThat(EmailUtils.normalize("   ")).isNull();
    }

    /** 核心不变式：大小写 / 首尾空格的变体必须收敛到同一个值，否则唯一索引拦不住「同邮箱两个账号」 */
    @Test
    void caseAndSpaceVariantsCollapseToOneValue() {
        assertThat(EmailUtils.normalize("A@X.com"))
                .isEqualTo(EmailUtils.normalize("a@x.com"))
                .isEqualTo(EmailUtils.normalize(" a@X.COM "));
    }
}
