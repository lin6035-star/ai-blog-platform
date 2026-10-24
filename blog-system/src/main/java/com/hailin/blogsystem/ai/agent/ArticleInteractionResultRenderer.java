package com.hailin.blogsystem.ai.agent;

import com.hailin.blogsystem.entity.dto.ArticleInteractionAction;
import com.hailin.blogsystem.entity.dto.ArticleInteractionOutcome;
import com.hailin.blogsystem.entity.dto.ArticleInteractionResult;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** 根据后端执行结果生成最终文案，不让 LLM 猜测动作是否成功。 */
@Component
public class ArticleInteractionResultRenderer {

    public String render(
            List<ArticleInteractionAction> requestedActions,
            List<ArticleInteractionResult> results
    ) {
        if (requestedActions == null || requestedActions.isEmpty()) {
            throw new IllegalArgumentException("请求动作不能为空");
        }
        if (results == null || results.size() != requestedActions.size()) {
            throw new IllegalArgumentException("动作结果必须完整");
        }

        Map<ArticleInteractionAction, ArticleInteractionResult> resultByAction =
                new EnumMap<>(ArticleInteractionAction.class);
        for (ArticleInteractionResult result : results) {
            if (result == null || result.actionType() == null) {
                throw new IllegalArgumentException("动作结果不能为空");
            }
            if (resultByAction.put(result.actionType(), result) != null) {
                throw new IllegalArgumentException("动作结果不能重复");
            }
        }
        if (!resultByAction.keySet().containsAll(requestedActions)) {
            throw new IllegalArgumentException("动作结果必须完整");
        }

        List<String> succeeded = requestedActions.stream()
                .map(resultByAction::get)
                .filter(result -> result.outcome() == ArticleInteractionOutcome.SUCCESS)
                .map(ArticleInteractionResult::summary)
                .toList();
        List<String> failed = requestedActions.stream()
                .map(resultByAction::get)
                .filter(result -> result.outcome() == ArticleInteractionOutcome.FAILED)
                .map(ArticleInteractionResult::summary)
                .toList();

        if (failed.isEmpty()) {
            return "已完成：" + join(succeeded) + "。";
        }
        if (succeeded.isEmpty()) {
            return "未完成：" + join(failed) + "。";
        }
        return "部分完成：" + join(succeeded) + "。未完成：" + join(failed) + "。";
    }

    private String join(List<String> summaries) {
        return String.join("；", summaries);
    }
}
