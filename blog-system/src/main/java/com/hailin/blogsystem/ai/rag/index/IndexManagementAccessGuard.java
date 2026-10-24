package com.hailin.blogsystem.ai.rag.index;

import com.hailin.blogsystem.config.BlogAiProperties;
import com.hailin.blogsystem.utils.Result;
import com.hailin.blogsystem.utils.UserContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 索引管理类操作（rebuild / repair / reindex / 健康巡检）的统一准入边界。
 *
 * <p>这类接口挂在 {@code /api/ai/**} 下时只保证“已登录”，而 **JWT 只解决身份认证、不代表管理员权限**；
 * 其中 repair / reindex 还会真实写入、删除 ES 文档。因此统一复用
 * {@code blog.ai.rag.rebuild.allowed-user-ids} 白名单，避免每个入口各写一份判断。
 */
@Component
@RequiredArgsConstructor
public class IndexManagementAccessGuard {

    private final BlogAiProperties blogAiProperties;

    /** 返回非 null 表示拒绝（调用方直接返回该结果）；返回 null 表示放行。 */
    public <T> Result<T> deny() {
        BlogAiProperties.Rebuild rebuild = blogAiProperties.getRag().getRebuild();
        if (!rebuild.isEnabled()) {
            return Result.error(403, "索引管理已关闭");
        }

        Long currentUserId = UserContext.get();
        if (currentUserId == null || !rebuild.getAllowedUserIds().contains(currentUserId)) {
            return Result.error(403, "无权操作");
        }
        return null;
    }
}
