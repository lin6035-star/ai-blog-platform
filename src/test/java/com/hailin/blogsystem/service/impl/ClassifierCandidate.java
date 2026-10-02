package com.hailin.blogsystem.service.impl;

import com.hailin.blogsystem.ai.ClassifierProbe;
import com.hailin.blogsystem.ai.TokenUsageAccumulator;
import com.hailin.blogsystem.entity.LearningPlans;
import com.hailin.blogsystem.entity.dto.AiIntent;
import com.hailin.blogsystem.entity.dto.PageContextDTO;

import java.util.List;

/**
 * P3 候选分类器（**测试侧**）：同一条请求，换的是"怎么组织 prompt、调几次模型"。
 *
 * <p><b>为什么放测试侧</b>：spike 只产数据，不替换 ACTIVE 路径（设计稿 §16.7-3）。
 * 生产 {@link AiIntentClassifierImpl} 连这个接口都不知道——谁胜出、要不要上线，
 * 是 spike 之后的决定，不是 spike 的一部分。
 *
 * <p><b>候选自己决定调用次数</b>：FULL 与 A 一次调用；B 是"路由 + 提取"两次。
 * 共用的调用链在 {@link ClassifierCandidateRunner}——所有候选走同一条链路，
 * 配对差值里只剩方案本身的差别（而不是链路差异）。
 */
public enum ClassifierCandidate {

    /**
     * 对照组：现状行为（19 块全装）。
     *
     * <p>没有它就没有"配对"——只有和现状比，才知道候选省了多少、错了什么。
     */
    FULL {
        @Override
        public AiIntent classifyOnce(ClassifierCandidateRunner runner, String message, PageContextDTO pageContext,
                                     List<LearningPlans> plans, TokenUsageAccumulator usage,
                                     ClassifierProbe probe, ClassifierProbe.CallRole role) {
            return runner.callOnce(AiIntentClassifierImpl::buildSystemPrompt,
                    message, pageContext, plans, usage, probe, role);
        }
    },

    /**
     * 候选 A：单调用 + 三层按需装载（见 {@link CandidateA}）。
     *
     * <p>repair 轮用现状的短 REPAIR prompt：它是已知的基线缺口（§6.6 说它不自包含），
     * 但那属于 P3 的另一个议题——这里必须同口径，否则"候选 A 的 repair 率更低"可能只是
     * 因为它换了一条更好的 repair prompt，而不是因为首轮 prompt 更准。
     */
    ON_DEMAND {
        @Override
        public AiIntent classifyOnce(ClassifierCandidateRunner runner, String message, PageContextDTO pageContext,
                                     List<LearningPlans> plans, TokenUsageAccumulator usage,
                                     ClassifierProbe probe, ClassifierProbe.CallRole role) {
            String pageType = pageContext == null ? null : pageContext.getPageType();
            return runner.callOnce(repair -> repair
                            ? AiIntentClassifierImpl.buildSystemPrompt(true)
                            : CandidateA.assemble(message, pageType).prompt(),
                    message, pageContext, plans, usage, probe, role);
        }
    },

    /**
     * 候选 B：Route + Extractor 双调用（见 {@link CandidateB}）。
     *
     * <p>它忽略 {@code role} 参数：调用角色由 B 自己决定（{@code ROUTE} / {@code EXTRACTOR}），
     * 因为"第一次调用在整条链里是什么角色"取决于方案结构，不该由外层猜。
     */
    ROUTE_EXTRACTOR {
        @Override
        public AiIntent classifyOnce(ClassifierCandidateRunner runner, String message, PageContextDTO pageContext,
                                     List<LearningPlans> plans, TokenUsageAccumulator usage,
                                     ClassifierProbe probe, ClassifierProbe.CallRole role) {
            return CandidateB.classify(runner, message, pageContext, plans, usage, probe);
        }
    };

    /** 报告里的名字（FULL / ON_DEMAND / ROUTE_EXTRACTOR） */
    public String id() {
        return name();
    }

    /** 一次完整分类——候选自己决定在这中间发几次模型调用 */
    public abstract AiIntent classifyOnce(ClassifierCandidateRunner runner, String message,
                                          PageContextDTO pageContext, List<LearningPlans> plans,
                                          TokenUsageAccumulator usage, ClassifierProbe probe,
                                          ClassifierProbe.CallRole role);
}
