package com.hailin.blogsystem;

import com.hailin.blogsystem.constants.RedisConstants;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GitHub OAuth 的 state 校验（防 Login CSRF）。
 *
 * <p>要防的具体攻击：攻击者自己走一遍授权拿到 {@code code}，把
 * {@code .../github/callback?code=xxx} 发给受害者；受害者点开后**登录成攻击者的账号**，
 * 之后写的东西全记在攻击者名下，攻击者反过来能看到他的一切。
 *
 * <p><b>这些用例刻意都不依赖外网</b>：无效 state 必须在"换 token"之前就被拒，
 * 所以根本走不到调 GitHub 那一步——如果哪天校验被挪到后面，这几条会立刻变慢甚至挂掉，
 * 那就是设计被破坏的信号。
 */
@SpringBootTest
@AutoConfigureMockMvc
class OAuthStateTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Test
    void authorizationUrlCarriesOneTimeState() throws Exception {
        String body = mockMvc.perform(get("/api/auth/github/url"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        String state = extractStateFromJson(body);
        assertThat(state)
                .as("授权 URL 必须带 state，否则 CSRF 防护无从谈起：%s", body)
                .isNotBlank();

        String key = RedisConstants.OAUTH_STATE_KEY_PREFIX + state;
        assertThat(redisTemplate.hasKey(key))
                .as("state 必须登记进 Redis 才能被回调校验")
                .isTrue();

        // TTL 要在预期区间内：太短用户走不完授权页，太长等于把 CSRF 窗口开大
        Long ttl = redisTemplate.getExpire(key);
        assertThat(ttl)
                .as("state 的 TTL 应约为 %d 分钟", RedisConstants.OAUTH_STATE_TTL_MINUTES)
                .isNotNull()
                .isBetween(1L, RedisConstants.OAUTH_STATE_TTL_MINUTES * 60);
    }

    /** 完全没有 state：立刻拒，且不碰 GitHub */
    @Test
    void callbackWithoutStateIsRejected() throws Exception {
        mockMvc.perform(get("/api/auth/github/callback").param("code", "fake-code"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/auth/github/callback?error=*"));
    }

    /**
     * 攻击者伪造 / 猜到的 state：同样立刻拒。
     *
     * <p>错误走**重定向带 error**而不是抛 JSON：这个端点是浏览器整页跳过来的，
     * 用户该看到一个页面。
     */
    @Test
    void callbackWithUnknownStateIsRejected() throws Exception {
        mockMvc.perform(get("/api/auth/github/callback")
                        .param("code", "fake-code")
                        .param("state", "definitely-not-a-registered-state"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/auth/github/callback?error=*"));
    }

    /**
     * 同一个 state 被消费后不能再用（一次性）。
     *
     * <p>这里直接删 Redis 里的 key 来模拟"已被用过"——不去真调一次带有效 state 的回调，
     * 因为那会走到换 token 的那一步、依赖外网。
     */
    @Test
    void stateCannotBeReusedAfterConsumption() throws Exception {
        String body = mockMvc.perform(get("/api/auth/github/url"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String state = extractStateFromJson(body);

        // 模拟第一个回调把它消费掉
        redisTemplate.delete(RedisConstants.OAUTH_STATE_KEY_PREFIX + state);

        mockMvc.perform(get("/api/auth/github/callback")
                        .param("code", "fake-code")
                        .param("state", state))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/auth/github/callback?error=*"));
    }

    /**
     * 从 {@code {"code":0,"data":{"url":"...state=xxx"}}} 里抠出 state。
     *
     * <p>不引 JSON 库：这个响应结构稳定且简单，多一个依赖不值得。
     */
    private String extractStateFromJson(String body) {
        int idx = body.indexOf("state=");
        if (idx < 0) {
            return "";
        }
        int start = idx + "state=".length();
        int end = start;
        while (end < body.length()) {
            char c = body.charAt(end);
            // state 是 Base64URL：字母数字和 - _，遇到其它字符就结束
            boolean keep = Character.isLetterOrDigit(c) || c == '-' || c == '_';
            if (!keep) {
                break;
            }
            end++;
        }
        return body.substring(start, end);
    }
}
