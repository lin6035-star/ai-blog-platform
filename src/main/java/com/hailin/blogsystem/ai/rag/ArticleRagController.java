package com.hailin.blogsystem.ai.rag;

import com.hailin.blogsystem.ai.rag.index.ArticleRagIndexHealthService;
import com.hailin.blogsystem.ai.rag.index.ArticleRagRebuildService;
import com.hailin.blogsystem.ai.rag.index.IndexManagementAccessGuard;
import com.hailin.blogsystem.entity.dto.ArticleRagContext;
import com.hailin.blogsystem.entity.dto.ArticleRagRebuildResult;
import com.hailin.blogsystem.entity.dto.ArticleRagRepairResult;
import com.hailin.blogsystem.entity.vo.ArticleRagConsistencyReportVO;
import com.hailin.blogsystem.entity.vo.ArticleRagIndexHealthVO;
import com.hailin.blogsystem.utils.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/ai/rag/articles")
@RequiredArgsConstructor
public class ArticleRagController {

    private final ArticleRagRetrieveService articleRagRetrieveService;
    private final ArticleRagConsistencyService articleRagConsistencyService;
    private final ArticleRagRebuildService articleRagRebuildService;
    private final ArticleRagIndexHealthService articleRagIndexHealthService;
    private final IndexManagementAccessGuard accessGuard;

    @PostMapping("/rebuild")
    public Result<ArticleRagRebuildResult> rebuildArticleIndex() throws Exception {
        Result<ArticleRagRebuildResult> denied = accessGuard.deny();
        if (denied != null) {
            return denied;
        }

        ArticleRagRebuildService.RebuildOutcome outcome = articleRagRebuildService.rebuild();
        return Result.success(new ArticleRagRebuildResult(outcome.chunkCount()));
    }

    @GetMapping("/search")  //RAG 检索调试接口
    public Result<List<ArticleRagContext>> searchArticleRag(@RequestParam String question){
        List<ArticleRagContext> contexts = articleRagRetrieveService.retrieve(question);

        return Result.success(contexts);
    }

    @GetMapping("/consistency")  //一致性巡检
    public Result<ArticleRagConsistencyReportVO> checkConsistency() {
        Result<ArticleRagConsistencyReportVO> denied = accessGuard.deny();
        if (denied != null) {
            return denied;
        }
        return Result.success(articleRagConsistencyService.check());
    }

    /**
     * 索引健康快照（只读）。
     *
     * <p>此前健康状态只存在于 {@code ArticleRagIndexHealthService} 的内存里、只能翻日志，
     * 线上遇到“RAG 为什么不返回”无法自查。这里把它暴露成显式接口：
     * {@code state=UNHEALTHY} 表示 dense 已被熔断，{@code SUSPECT} 表示首次巡检未完成（仍会检索）。
     */
    @GetMapping("/health")
    public Result<ArticleRagIndexHealthVO> indexHealth() {
        Result<ArticleRagIndexHealthVO> denied = accessGuard.deny();
        if (denied != null) {
            return denied;
        }

        return Result.success(ArticleRagIndexHealthVO.from(
                articleRagIndexHealthService.current(),
                articleRagIndexHealthService.denseSearchAllowed(),
                articleRagIndexHealthService.incrementalWritesAllowed()
        ));
    }

    @PostMapping("/consistency/repair")  //手动修复接口
    public Result<ArticleRagRepairResult> repairConsistency() {
        Result<ArticleRagRepairResult> denied = accessGuard.deny();
        if (denied != null) {
            return denied;
        }
        return Result.success(articleRagConsistencyService.repair());
    }

}
