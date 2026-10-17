package com.hailin.blogsystem.utils;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 取客户端真实 IP。
 *
 * <p><b>信任边界：只信反向代理用 {@code $remote_addr} 覆盖写入的 {@code X-Real-IP}。</b>
 *
 * <p>2026-10-17 上线审计的修复：改造前按
 * {@code CF-Connecting-IP → X-Forwarded-For → X-Real-IP} 的顺序取值，而这三个头
 * <b>客户端都能自己写</b>，Nginx 配置里 {@code CF-Connecting-IP $http_cf_connecting_ip}
 * 又是把客户端头原样转发。后果是攻击者每次请求换一个伪造 IP 就能绕过所有基于 IP 的
 * 限流（游客 AI 5 次/分钟、登录失败 IP 维度、密码重置 IP 维度），实测可无限调用。
 *
 * <p>修法是把「谁是可信代理」交给部署层：Nginx 用
 * {@code proxy_set_header X-Real-IP $remote_addr;}（<b>覆盖</b>而非追加）写入，
 * 客户端带同名头进来会被 Nginx 覆盖掉。代码只读这一个头，读不到就退回
 * {@code request.getRemoteAddr()}（直连场景）。
 *
 * <p>⚠️ 若将来前面挂 Cloudflare，<b>不要</b>在代码里恢复信任 {@code CF-Connecting-IP}：
 * 正确做法是在 Nginx 侧做可信源判定（安全组只允许 CF 段 + {@code set_real_ip_from}），
 * 再由 Nginx 覆盖写 {@code X-Real-IP}。代码里的信任集合保持只有一项。
 */
public final class ClientIpUtils {

    private static final String UNKNOWN = "unknown";

    /** 反向代理覆盖写入的可信头（见类注释） */
    private static final String TRUSTED_IP_HEADER = "X-Real-IP";

    private ClientIpUtils() {
    }

    public static String getClientIp(HttpServletRequest request) {
        String ip = firstValidIp(request.getHeader(TRUSTED_IP_HEADER));
        if (ip == null) {
            ip = firstValidIp(request.getRemoteAddr());
        }
        return ip == null ? UNKNOWN : ip;
    }

    private static String firstValidIp(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        for (String candidate : value.split(",")) {
            String ip = candidate.trim();
            if (!ip.isEmpty() && !UNKNOWN.equalsIgnoreCase(ip)) {
                return ip;
            }
        }
        return null;
    }
}
