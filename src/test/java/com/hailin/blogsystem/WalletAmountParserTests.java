package com.hailin.blogsystem;

import com.hailin.blogsystem.ai.agent.WalletAmountParser;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 充值金额解析。
 *
 * <p>它是「钱不经 LLM」这条设计决策的落点，所以边界要钉死：抠不到、抠出多个，
 * 都必须落到「问用户」而不是「挑一个」。**猜错的方向是钱**。
 */
class WalletAmountParserTests {

    private static void assertSingle(String text, long expected) {
        WalletAmountParser.Result result = WalletAmountParser.parse(text);
        assertThat(result.kind())
                .as("「%s」应当解析出一个金额", text)
                .isEqualTo(WalletAmountParser.Kind.SINGLE);
        assertThat(result.yuan()).isEqualTo(expected);
    }

    private static void assertNotSingle(String text, WalletAmountParser.Kind expected) {
        assertThat(WalletAmountParser.parse(text).kind())
                .as("「%s」", text)
                .isEqualTo(expected);
    }

    // ---------- 阿拉伯数字 ----------

    @Test
    void parsesArabicNumbers() {
        assertSingle("帮我充10块", 10);
        assertSingle("帮我充 10 元", 10);
        assertSingle("充值100", 100);
        assertSingle("充1000块钱", 1000);
        assertSingle("给我充1元", 1);
    }

    // ---------- 中文数字 ----------

    @Test
    void parsesChineseNumbers() {
        assertSingle("帮我充十块", 10);
        assertSingle("帮我充十五块", 15);
        assertSingle("充二十元", 20);
        assertSingle("充一百块", 100);
        assertSingle("充一百二十元", 120);
        assertSingle("充一千块", 1000);
        assertSingle("充两百元", 200);
        assertSingle("充两块钱", 2);
    }

    /** 同一句话里出现两次同一个数字，只算一个金额 */
    @Test
    void deduplicatesTheSameAmount() {
        assertSingle("充10块，对就是10块", 10);
    }

    // ---------- 三态 ----------

    @Test
    void returnsNoneWhenNoAmountMentioned() {
        assertNotSingle("帮我充点钱", WalletAmountParser.Kind.NONE);
        assertNotSingle("我想充值", WalletAmountParser.Kind.NONE);
        assertNotSingle("充值", WalletAmountParser.Kind.NONE);
        assertNotSingle("", WalletAmountParser.Kind.NONE);
        assertNotSingle(null, WalletAmountParser.Kind.NONE);
    }

    /**
     * 中文数字**必须带单位**才认。
     *
     * <p>不要求单位的话，「我一点也不困」里的「一」会被算成 1 元。没单位的说法落到
     * NONE 去追问是安全的——用户补一句「十块」就行。
     */
    @Test
    void chineseNumberWithoutUnitIsNotAnAmount() {
        assertNotSingle("帮我充十", WalletAmountParser.Kind.NONE);
        assertNotSingle("我一点也不困", WalletAmountParser.Kind.NONE);
    }

    /** 多个**不同**金额 → 歧义。挑一个猜是钱的错误方向，宁可多问一句 */
    @Test
    void multipleDifferentAmountsAreAmbiguous() {
        assertNotSingle("先充10块，等下再充20块", WalletAmountParser.Kind.AMBIGUOUS);
        assertNotSingle("充10块，我昨天花了500", WalletAmountParser.Kind.AMBIGUOUS);
        assertNotSingle("充十块或者二十块都行", WalletAmountParser.Kind.AMBIGUOUS);
    }

    /** 「充 0 块」解析得出 0，由上层按区间规则拒绝——解析器不越权替它判断合法性 */
    @Test
    void parsesZeroSoThatRangeValidationCanRejectIt() {
        assertSingle("充0块", 0);
    }
}
