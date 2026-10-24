package com.hailin.blogsystem;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hailin.blogsystem.entity.dto.AiIntent;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 分类器输出的 JSON → {@link AiIntent} 的字段契约。
 *
 * <p><b>为什么要有这组用例</b>：分类器提示词的**指令区**要求模型输出 {@code keyword}
 * （见 {@code ClassifierPromptBlocks}），而 DTO 上的字段名是 {@code keyWord}
 * ——大小写差一个字母。没有别名时 Jackson 把 {@code keyword} 当**未知字段静默丢弃**
 * （Boot 默认 {@code FAIL_ON_UNKNOWN_PROPERTIES=false}），于是字段恒为 null。
 *
 * <p>它一次掐掉两条链路，而且**完全没有痕迹**（无异常、无日志、"检索照常工作"）：
 * <ul>
 *   <li>关键词检索拿不到分类器给的词，只能退回"用原问题套停用词表"——
 *       "有没有关于 Java 的文章"被削成 "Java"</li>
 *   <li>{@code buildMysqlFallbackContexts} 见 null 直接返回空，**整条兜底从未执行过**</li>
 * </ul>
 *
 * <p>所以这组断言的作用是：谁把 {@code @JsonAlias} "顺手"删掉，这里立刻红。
 */
class AiIntentJsonContractTests {

    /**
     * 刻意关掉"未知字段即失败"——**与应用里的 Boot 默认行为对齐**。
     * 用裸 {@code new ObjectMapper()} 的话默认是开启的，那测的就不是生产的行为了。
     */
    private final ObjectMapper objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Test
    void acceptsTheLowercaseKeywordThePromptActuallyAsksFor() throws Exception {
        // 提示词指令区要求模型输出的就是这个拼写
        AiIntent intent = objectMapper.readValue(
                "{\"intent\":\"ARTICLE_SEARCH\",\"keyword\":\"Java注解\"}", AiIntent.class);

        assertThat(intent.getKeyWord())
                .as("提示词说 keyword、DTO 叫 keyWord —— 靠 @JsonAlias 接住")
                .isEqualTo("Java注解");
    }

    @Test
    void stillAcceptsCamelCaseKeyWord() throws Exception {
        AiIntent intent = objectMapper.readValue(
                "{\"intent\":\"ARTICLE_SEARCH\",\"keyWord\":\"Redis 缓存\"}", AiIntent.class);

        assertThat(intent.getKeyWord()).isEqualTo("Redis 缓存");
    }

    @Test
    void acceptsBothSpellingsAtOnceWithoutFailing() throws Exception {
        // 模型两种都吐时不该让解析失败（实际取哪个不影响正确性，两者本来是同一个东西）
        AiIntent intent = objectMapper.readValue(
                "{\"intent\":\"ARTICLE_SEARCH\",\"keyword\":\"Java\",\"keyWord\":\"Java\"}", AiIntent.class);

        assertThat(intent.getKeyWord()).isEqualTo("Java");
    }

    @Test
    void toleratesUnknownFields() throws Exception {
        // 模型多吐字段是常态，不该让整个分类结果解析失败
        AiIntent intent = objectMapper.readValue(
                "{\"intent\":\"GENERAL_CHAT\",\"someFieldAddedLater\":123}", AiIntent.class);

        assertThat(intent.getIntent()).isEqualTo("GENERAL_CHAT");
    }
}
