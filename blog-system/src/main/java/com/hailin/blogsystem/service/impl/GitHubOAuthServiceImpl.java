package com.hailin.blogsystem.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hailin.blogsystem.constants.BlogConstants;
import com.hailin.blogsystem.constants.RedisConstants;
import com.hailin.blogsystem.entity.Users;
import com.hailin.blogsystem.exception.BusinessException;
import com.hailin.blogsystem.mapper.LoginMapper;
import com.hailin.blogsystem.service.GitHubOAuthService;
import com.hailin.blogsystem.service.WalletService;
import com.hailin.blogsystem.utils.EmailUtils;
import com.hailin.blogsystem.utils.JwtUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class GitHubOAuthServiceImpl extends ServiceImpl<LoginMapper, Users>
        implements GitHubOAuthService {

    private final JwtUtil jwtUtil;
    private final WalletService walletService;
    private final StringRedisTemplate redisTemplate;
    private final RestClient restClient;

    /**
     * 生成 OAuth state 的随机源。
     *
     * <p>state 的强度不在于"抗暴力猜"（它只活 5 分钟、一次一换），而在于**不可预测**：
     * 可预测的话攻击者能提前算好 state，把 code + state 一起发给受害者，
     * 那 CSRF 防护就等于没做。所以仍然用 SecureRandom。
     */
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    @Value("${github.oauth.client-id}")
    private String clientId;

    @Value("${github.oauth.client-secret}")
    private String clientSecret;

    @Value("${github.oauth.redirect-uri}")
    private String redirectUri;

    public GitHubOAuthServiceImpl(JwtUtil jwtUtil,
                                  WalletService walletService,
                                  StringRedisTemplate redisTemplate) {
        this.jwtUtil = jwtUtil;
        this.walletService = walletService;
        this.redisTemplate = redisTemplate;
        this.restClient = RestClient.builder().build();
    }

    @Override
    public String getAuthorizationUrl() {
        String state = newState();

        return "https://github.com/login/oauth/authorize"
                + "?client_id=" + clientId
                + "&redirect_uri=" + redirectUri
                // user:email 是「按邮箱认人」的前提：少了它调 /user/emails 会 403，
                // 而 /user 返回的 email 字段在用户把邮箱设为私密时是 null
                + "&scope=read:user%20user:email"
                + "&state=" + state;
    }

    /** 生成并登记一个一次性 state（5 分钟有效，回调时消费） */
    private String newState() {
        byte[] bytes = new byte[16];
        SECURE_RANDOM.nextBytes(bytes);
        String state = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);

        redisTemplate.opsForValue().set(
                RedisConstants.OAUTH_STATE_KEY_PREFIX + state,
                "1",
                Duration.ofMinutes(RedisConstants.OAUTH_STATE_TTL_MINUTES));

        return state;
    }

    /**
     * 消费 state：**删除成功才算通过**。
     *
     * <p>用 DEL 而不是 GET + DEL，是因为 DEL 本身原子：两个并发回调只有一个能删成功，
     * 另一个必然失败。GET + DEL 的话两边都可能读到同一份，"一次性"就破了。
     *
     * <p>不区分"不存在"和"已被用过"：两者都是无效，没必要给攻击者更多信息。
     */
    private void consumeStateOrThrow(String state) {
        if (state == null || state.isBlank()) {
            throw new BusinessException(BlogConstants.ErrorCode.BAD_REQUEST,
                    "登录状态已失效，请重新发起 GitHub 登录");
        }

        Boolean removed;
        try {
            removed = redisTemplate.delete(RedisConstants.OAUTH_STATE_KEY_PREFIX + state);
        } catch (RuntimeException e) {
            // ⚠️ Redis 异常时**不能放行**：state 校验是这道 CSRF 防线的唯一依据，
            // 这里 fail-open 等于把刚补上的洞重新打开（跟限流场景的取舍**相反**——
            // 限流挂了可以少挡一会儿，CSRF 防线挂了就等于没有）
            log.error("[GITHUB] state 校验失败（Redis 异常），拒绝本次回调", e);
            throw new BusinessException(BlogConstants.ErrorCode.SERVER_ERROR,
                    "登录服务暂不可用，请稍后重试");
        }

        if (!Boolean.TRUE.equals(removed)) {
            log.warn("[GITHUB] state 无效或已被使用，拒绝回调");
            throw new BusinessException(BlogConstants.ErrorCode.BAD_REQUEST,
                    "登录状态已失效，请重新发起 GitHub 登录");
        }
    }

    @Override
    public String handleCallback(String code, String state) {
        // 0. **先校验 state**，而且必须排在所有外部调用之前：
        //    否则攻击者拿别人的 code 也能走到"换 token"那一步，防线就形同虚设
        consumeStateOrThrow(state);

        // 1. 用 code 换 GitHub access_token
        String accessToken = exchangeCodeForToken(code);

        // 2. 用 access_token 获取 GitHub 用户信息
        Map<String, Object> githubUser = fetchGitHubUser(accessToken);

        // 3. 取主邮箱（拿不到就是 null，见 fetchPrimaryVerifiedEmail）
        String email = fetchPrimaryVerifiedEmail(accessToken);

        // 4. 查找或创建本地用户。邮箱在这里的作用是把他 GitHub 身份**收敛到已有的密码账号**上，
        //    而不是又建一个"同邮箱的第二个账号"
        Users user = findOrCreateUser(githubUser, email);

        // 5. 生成 JWT（带该用户当前代次：改密后旧代次会被拦截器拒掉）
        return jwtUtil.generateToken(user.getId(), user.tokenVersionOrDefault());
    }

    private String exchangeCodeForToken(String code) {
        MultiValueMap<String, String> formData = new LinkedMultiValueMap<>();
        formData.add("client_id", clientId);
        formData.add("client_secret", clientSecret);
        formData.add("code", code);
        formData.add("redirect_uri", redirectUri);

        try {
            Map<String, Object> response = restClient.post()
                    .uri("https://github.com/login/oauth/access_token")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(formData)
                    .retrieve()
                    .body(Map.class);

            if (response == null || response.get("access_token") == null) {
                String error = response != null ? String.valueOf(response.get("error_description")) : "未知错误";
                throw new BusinessException(BlogConstants.ErrorCode.BAD_REQUEST,
                        "GitHub 授权失败: " + error);
            }

            return String.valueOf(response.get("access_token"));
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(BlogConstants.ErrorCode.SERVER_ERROR,
                    "GitHub 令牌交换失败: " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> fetchGitHubUser(String accessToken) {
        try {
            Map<String, Object> userInfo = restClient.get()
                    .uri("https://api.github.com/user")
                    .header("Authorization", "Bearer " + accessToken)
                    .header("Accept", "application/json")
                    .header("User-Agent", "blog-system")
                    .retrieve()
                    .body(Map.class);

            if (userInfo == null || userInfo.get("id") == null) {
                throw new BusinessException(BlogConstants.ErrorCode.SERVER_ERROR,
                        "获取 GitHub 用户信息失败");
            }

            return userInfo;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(BlogConstants.ErrorCode.SERVER_ERROR,
                    "获取 GitHub 用户信息失败: " + e.getMessage());
        }
    }

    /**
     * 取该 GitHub 用户的**主邮箱，且必须是已验证的**。
     *
     * <p><b>为什么必须单独调一次 /user/emails</b>：{@code /user} 返回的 {@code email}
     * 在用户把邮箱设为私密时是 {@code null}——而这个设置在 GitHub 新账号里默认就开着，
     * 只靠它匹配等于相当一部分用户永远匹配不上。该接口需要 {@code user:email} scope
     * （见 {@link #getAuthorizationUrl()}）。
     *
     * <p><b>只认 primary + verified</b>：未验证的邮箱是用户随手填的，可能是别人的地址——
     * 拿它去匹配账号，等于"谁把这个邮箱填进自己的 GitHub，谁就拿到那个账号"。
     *
     * @return 归一化后的邮箱；拿不到（未授权 / 未验证 / 接口异常）返回 {@code null}
     */
    @SuppressWarnings("unchecked")
    private String fetchPrimaryVerifiedEmail(String accessToken) {
        try {
            List<Map<String, Object>> emails = restClient.get()
                    .uri("https://api.github.com/user/emails")
                    .header("Authorization", "Bearer " + accessToken)
                    .header("Accept", "application/json")
                    .header("User-Agent", "blog-system")
                    .retrieve()
                    .body(List.class);

            if (emails == null) {
                return null;
            }

            return emails.stream()
                    .filter(e -> Boolean.TRUE.equals(e.get("primary")))
                    .filter(e -> Boolean.TRUE.equals(e.get("verified")))
                    .map(e -> EmailUtils.normalize(e.get("email") == null ? null : String.valueOf(e.get("email"))))
                    // 归一化会把空串变成 null，这里必须滤掉：否则会拿 null 去匹配
                    // "email 为空的账号"，把身份绑到某个没填邮箱的用户身上
                    .filter(java.util.Objects::nonNull)
                    .findFirst()
                    .orElse(null);
        } catch (Exception e) {
            // 拿不到邮箱不是致命错误：退回"只按 githubId 认人"的旧行为——
            // 用户照样能登录，只是不会自动绑到他已有的密码账号上
            log.warn("[GITHUB] 获取邮箱失败，本次只按 githubId 匹配: {}", e.getMessage());
            return null;
        }
    }

    private Users findOrCreateUser(Map<String, Object> githubUser, String email) {
        Long githubId = Long.valueOf(String.valueOf(githubUser.get("id")));
        String login = String.valueOf(githubUser.get("login"));
        String name = githubUser.get("name") != null ? String.valueOf(githubUser.get("name")) : null;
        String avatarUrl = githubUser.get("avatar_url") != null
                ? String.valueOf(githubUser.get("avatar_url")) : "";
        String bio = githubUser.get("bio") != null ? String.valueOf(githubUser.get("bio")) : "";

        // 1. 已绑定过的 GitHub 身份。
        //    **只按 github_id 查，不带 login_type**：login_type 表达的是"注册来源"，
        //    而绑定不会改它（改了用户就用不了密码登录）——带上它就会出现
        //    「刚绑过的账号，第二次 GitHub 登录又找不到自己」
        Users user = lambdaQuery().eq(Users::getGithubId, githubId).one();

        if (user != null) {
            // 更新最新信息
            user.setNickname(name != null ? name : login);
            user.setAvatarUrl(avatarUrl);
            user.setBio(bio);
            user.setUpdatedAt(LocalDateTime.now());
            updateById(user);
            return user;
        }

        // 2. 没绑过 GitHub，但邮箱命中了一个已有账号 → **绑定到它，而不是新建**。
        //    这是"先用邮箱注册、后来改用 GitHub 登录"收敛成同一个账号的关键；
        //    不做的话同一邮箱会存在两个账号，文章 / 评论 / 钱包全部裂开。
        if (email != null) {
            Users byEmail = lambdaQuery().eq(Users::getEmail, email).one();
            if (byEmail != null) {
                byEmail.setGithubId(githubId);
                byEmail.setUpdatedAt(LocalDateTime.now());
                // **刻意不覆盖昵称 / 头像 / bio**：账号是他自己的，
                // GitHub 上的资料不该盖掉他在站内改过的设置
                updateById(byEmail);
                log.info("[GITHUB] 按邮箱把 GitHub 身份绑到已有账号: userId={}", byEmail.getId());
                return byEmail;
            }
        }

        // 3. 全新用户。带上邮箱——他以后要用「忘记密码」就得有邮箱
        user = new Users();
        user.setUsername(login);
        user.setNickname(name != null ? name : login);
        user.setAvatarUrl(avatarUrl);
        user.setBio(bio);
        user.setGithubId(githubId);
        user.setLoginType("github");
        user.setPasswordHash("");
        user.setEmail(email);
        user.setCreatedAt(LocalDateTime.now());
        user.setUpdatedAt(LocalDateTime.now());
        save(user);
        // 这里没有外层事务（handleCallback 在此之前有两次调 GitHub 的 HTTP 请求，
        // 包进事务就成了「长事务 + 事务内远程调用」），所以送礼是独立事务。
        // 极端情况下会留下一个「账号建好了但没额度」的用户——赠品本就是白送的，
        // 他去钱包页充值即可补上，不值得为此引入自注入 + 事务方法拆分。
        walletService.grantInitialCredit(user.getId());

        return user;
    }
}
