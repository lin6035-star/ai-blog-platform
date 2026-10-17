package com.hailin.blogsystem.service;

public interface GitHubOAuthService {

    /**
     * 生成 GitHub 授权页 URL。
     *
     * <p>会同时生成一个**一次性 state** 存进 Redis 并拼在 URL 上——回调时校验，
     * 用来挡 Login CSRF：不校验的话，攻击者可以把"自己的 code"发给受害者，
     * 诱导他登录成攻击者的账号。
     */
    String getAuthorizationUrl();

    /**
     * 处理 GitHub 回调，返回 JWT token。
     *
     * @param code  授权码
     * @param state 发起登录时生成的随机串，**必须与 Redis 里的那份一致且只能消费一次**
     */
    String handleCallback(String code, String state);
}
