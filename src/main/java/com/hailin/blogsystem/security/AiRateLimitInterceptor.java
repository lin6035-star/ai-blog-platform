package com.hailin.blogsystem.security;

import com.hailin.blogsystem.config.BlogAiProperties;
import com.hailin.blogsystem.utils.ClientIpUtils;
import com.hailin.blogsystem.utils.UserContext;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 接口限流拦截器：只拦「贵」的或者「天然会被刷」的写入口。
 *
 * - 登录用户按 userId 限流，游客按 IP 限流（游客使用更低限额）
 * - 在 Controller 之前拦截（SSE 场景在进入业务流前就拒绝，不会等 LLM 开始生成）
 * - 超限抛 RateLimitExceededException，由 GlobalExceptionHandler 转 HTTP 429
 *
 * 桶：chat / workflow / rag（消耗 LLM 与 ES）、seckill（发钱，且会被脚本盯上）。
 *
 * 不限制：查询历史消息 / Workflow 状态 / 取消 / **秒杀结果轮询** /
 * 普通文章浏览 / 点赞收藏评论。
 *
 * <p>2026-10-17 上线审计追加的三道闸门（都在本拦截器里，Controller 之前）：
 * <ol>
 *   <li><b>全站 AI 每分钟上限</b>：分类器仍同步占 Tomcat 请求线程（改造前 200 并发就能拖垮整站），
 *       这个闸门把进入业务的速率压住。秒杀抢购不占这个额度。</li>
 *   <li><b>游客每 IP 日额度</b>：游客免费保留后，单来源不能被无限刷。</li>
 *   <li><b>全站游客日预算</b>：兜底分布式/代理池刷，超限后游客 AI 关闭到次日 0 点，登录用户不受影响。</li>
 * </ol>
 * 这三道闸门都依赖 {@code ClientIpUtils} 只信 {@code X-Real-IP}（由 Nginx 覆盖写入）——
 * 改造前 IP 取自客户端可伪造的头，闸门形同虚设。
 */
@Component
public class AiRateLimitInterceptor implements HandlerInterceptor {

    private static final String BUCKET_CHAT = "chat";
    private static final String BUCKET_WORKFLOW = "workflow";
    private static final String BUCKET_RAG = "rag";
    private static final String BUCKET_SECKILL = "seckill";

    private static final String GUEST_IP_BUCKET = "guest-ip";
    private static final String GUEST_GLOBAL_BUCKET = "guest-global";
    private static final String GLOBAL_BUCKET = "global";

    private final AiRateLimiter rateLimiter;
    private final BlogAiProperties properties;

    public AiRateLimitInterceptor(
            AiRateLimiter rateLimiter,
            BlogAiProperties properties
    ) {
        this.rateLimiter = rateLimiter;
        this.properties = properties;
    }

    @Override
    public boolean preHandle(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler
    ) {
        /*
         * SSE（Controller 返回 Flux）请求结束时，容器会以 ASYNC 类型再 dispatch 一次，
         * preHandle 因此被第二次调用。不跳过的话**每次 AI 请求都要计两次额度**：
         * 2026-10-17 实测游客日额度配 2 次、实际只放行了 1 次，Redis 计数 6 个请求变 7；
         * 原有 chat 桶「10 次/分钟」实际上也只有 5 次。
         * 这道判断同时修掉旧限流的隐性减半。
         */
        if (request.getDispatcherType() == DispatcherType.ASYNC) {
            return true;
        }

        BlogAiProperties.RateLimit config = properties.getRateLimit();
        if (config == null || !config.isEnabled()) {
            return true;
        }

        String bucket = resolveBucket(request);
        if (bucket == null) {
            return true;
        }

        // 1) 全站 AI 每分钟上限（秒杀是独立桶：50 库存的正常抢购就可能超过 120/分钟，
        //    且它的正确性由 Lua + 一人一单保证，不该被这里的 AI 闸门误伤）
        if (!BUCKET_SECKILL.equals(bucket) && config.getGlobalPerMinute() > 0) {
            boolean globalAllowed = rateLimiter.tryAcquire(
                    GLOBAL_BUCKET, "all", "all", config.getGlobalPerMinute(), 60);
            if (!globalAllowed) {
                throw new RateLimitExceededException(60, "AI 服务整体繁忙，请稍后再试");
            }
        }

        Long userId = UserContext.get();

        // 2) 游客日额度 + 全站游客日预算（只有游客走）
        if (userId == null) {
            enforceGuestDailyQuota(request);
        }

        // 3) 原有 per-user / per-IP 窗口限流
        BlogAiProperties.Limit limit = switch (bucket) {
            case BUCKET_CHAT -> config.getChat();
            case BUCKET_WORKFLOW -> config.getWorkflow();
            case BUCKET_RAG -> config.getRag();
            case BUCKET_SECKILL -> config.getSeckill();
            default -> null;
        };

        if (limit == null || limit.getLimit() <= 0) {
            return true;
        }

        String type;
        String identity;

        if (userId != null) {
            type = "user";
            identity = String.valueOf(userId);
        } else {
            // 游客：按 IP 限流，使用登录用户的一半限额（下限 1）
            type = "ip";
            identity = ClientIpUtils.getClientIp(request);
            int guestLimit = Math.max(1, limit.getLimit() / 2);
            limit = new BlogAiProperties.Limit(guestLimit, limit.getWindowSeconds());
        }

        boolean allowed = rateLimiter.tryAcquire(
                bucket,
                type,
                identity,
                limit.getLimit(),
                limit.getWindowSeconds()
        );

        if (!allowed) {
            throw new RateLimitExceededException(limit.getWindowSeconds());
        }

        return true;
    }

    /**
     * 游客日额度：单 IP 日额度 + 全站游客日预算。超限拒绝到次日 0 点。
     *
     * <p>顺序：先 IP 后全局。IP 已超限时直接拒绝，不再消耗全局预算计数；
     * 反过来全局已满时会让当前 IP 也计一次，但此时游客本来也用不了，无实质影响。
     */
    private void enforceGuestDailyQuota(HttpServletRequest request) {
        BlogAiProperties.Guest guest = properties.getGuest();
        if (guest == null) {
            return;
        }

        long secondsToNextDay = secondsUntilNextDay();
        String ip = ClientIpUtils.getClientIp(request);

        if (guest.getDailyLimitPerIp() > 0
                && !rateLimiter.tryAcquireDaily(
                        GUEST_IP_BUCKET, ip, guest.getDailyLimitPerIp(), secondsToNextDay)) {
            throw new RateLimitExceededException(
                    secondsToNextDay,
                    "游客每日 AI 次数已用完，请明天再试或登录后继续使用"
            );
        }

        if (guest.getGlobalDailyLimit() > 0
                && !rateLimiter.tryAcquireDaily(
                        GUEST_GLOBAL_BUCKET, "all", guest.getGlobalDailyLimit(), secondsToNextDay)) {
            throw new RateLimitExceededException(
                    secondsToNextDay,
                    "游客 AI 今日额度已满，请登录后继续使用"
            );
        }
    }

    /** 距次日 0 点的秒数（下限 60，避免 23:59:59 时 TTL 变成 0 导致立刻过期） */
    private long secondsUntilNextDay() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime nextDayStart = now.toLocalDate().plusDays(1).atStartOfDay();
        long seconds = Duration.between(now, nextDayStart).getSeconds();
        return Math.max(60, seconds);
    }

    /**
     * 按 URI 分类限流桶；不消耗 LLM / ES 的请求返回 null（不限流）。
     */
    private String resolveBucket(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String method = request.getMethod();

        if ("/api/ai/chat/stream".equals(uri)) {
            return BUCKET_CHAT;
        }

        if ("/api/ai/rag/articles/search".equals(uri)) {
            return BUCKET_RAG;
        }

        // Workflow：只拦"创建"（POST），GET 状态查询不拦。
        // 确认类操作（approve/reject/retry/cancel）是流程内正常交互，
        // 次数天然受 Workflow 步骤数限制，不占用创建入口配额，否则
        // 一个流程连续确认几次就被 429 误伤。
        // 注意：流式确认端点是 /{id}/approve/stream，用包含判断覆盖两种形态。
        if (uri.startsWith("/api/ai/workflows/")) {
            if ("GET".equalsIgnoreCase(method)) {
                return null;
            }
            if (uri.contains("/cancel")
                    || uri.contains("/approve")
                    || uri.contains("/reject")
                    || uri.contains("/retry")) {
                return null;
            }
            return BUCKET_WORKFLOW;
        }

        // 秒杀：只拦「抢购」这一个动作。结果查询是前端每秒轮询的正常行为，
        // 把它也算进配额，等于用户还没抢到就先被 429 了
        if ("POST".equalsIgnoreCase(method)
                && uri.startsWith("/api/seckill/")
                && uri.endsWith("/grab")) {
            return BUCKET_SECKILL;
        }

        return null;
    }
}
