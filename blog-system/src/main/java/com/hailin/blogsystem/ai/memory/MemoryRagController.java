package com.hailin.blogsystem.ai.memory;

import com.hailin.blogsystem.ai.rag.index.IndexManagementAccessGuard;
import com.hailin.blogsystem.utils.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Memory 向量运维入口。
 *
 * <p>重刷会真实写入 ES 文档，属于“索引管理”动作，因此与文章侧 rebuild / repair
 * 共用同一个 allowlist 边界（{@link IndexManagementAccessGuard}）——JWT 只代表已登录，
 * 不代表管理员权限。
 */
@RestController
@RequestMapping("/api/ai/rag/memories")
@RequiredArgsConstructor
public class MemoryRagController {

    private final MemoryRagReindexService memoryRagReindexService;
    private final IndexManagementAccessGuard accessGuard;

    /** 按 MySQL 权威数据重刷存量记忆向量；幂等，可重复执行。 */
    @PostMapping("/reindex")
    public Result<MemoryRagReindexService.ReindexOutcome> reindex() {
        Result<MemoryRagReindexService.ReindexOutcome> denied = accessGuard.deny();
        if (denied != null) {
            return denied;
        }
        return Result.success(memoryRagReindexService.reindex());
    }
}
