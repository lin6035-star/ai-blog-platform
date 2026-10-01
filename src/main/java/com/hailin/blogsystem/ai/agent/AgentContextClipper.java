package com.hailin.blogsystem.ai.agent;

import java.util.List;

/**
 * Agent 证据上下文裁剪（2026-09-30，Token 工程 P0-a）。
 *
 * <p><b>为什么单独成类</b>：这段逻辑原先在 {@link AbstractAgentRuntime} 与
 * {@link ArticleEvidenceVerifier} 各有一份**逐字相同**的复制粘贴——本次缺陷（满载时丢最新证据）
 * 因此要改两处；而 Verifier 的证据预算后续计划收窄到 2500~3200（Token 设计稿 §9.2），
 * 两份继续各自维护必然再次分叉。抽成带 {@code maxChars} 的单一实现，调用方各传自己的预算。
 *
 * <p><b>语义（修复前 → 修复后）</b>：
 * <ul>
 *   <li>修复前：从**最早**一条开始累加，达到上限就停止 → 满载时保留旧材料、**丢掉最新证据**。
 *       这与证据收敛门「Verifier 判 NEED_MORE → 补查 → 用新证据回答」的机制**直接对冲**：
 *       补到的新证据最可能被截掉 → 继续被拦 → 直到 maxSteps 用尽走保守收尾。</li>
 *   <li>修复后：先按**最新优先**选出一个连续窗口（装得下的最新若干条），再按**原时间顺序**输出。</li>
 * </ul>
 *
 * <p><b>三条不变量</b>：
 * <ol>
 *   <li>输出总长不超过 {@code maxChars}；</li>
 *   <li>条目不重排——窗口内仍是原时间顺序，不能把列表倒序发给模型；</li>
 *   <li>单条即超预算时退化为「截断保留最新一条」，**不返回空上下文**（空上下文会静默丢光证据）。</li>
 * </ol>
 *
 * <p>包内可见：只服务 {@code ai.agent} 包内的两个调用点，不作为公共 API。
 */
final class AgentContextClipper {

    private AgentContextClipper() {
    }

    /**
     * 取「最新优先」的连续窗口，并按原时间顺序拼接。
     *
     * @param observations 按时间先后排列的证据列表；null 条目按不存在处理
     * @param maxChars     累计上限（含每条追加的换行）
     */
    static String latestWindow(List<String> observations, int maxChars) {
        if (observations == null || observations.isEmpty() || maxChars <= 0) {
            return "";
        }

        // ① 从最新往前累计，直到装不下为止 → 得到一个「最新优先」的连续窗口 [start, size)
        int total = 0;
        int start = observations.size();
        for (int i = observations.size() - 1; i >= 0; i--) {
            String observation = observations.get(i);
            if (observation == null) {
                continue;
            }
            int length = observation.length() + 1;   // +1 = 拼接时追加的换行
            if (total + length > maxChars) {
                break;
            }
            total += length;
            start = i;
        }

        if (start == observations.size()) {
            // 一条都装不下（单条即超预算）：截断保留最新一条，不返回空上下文
            for (int i = observations.size() - 1; i >= 0; i--) {
                String latest = observations.get(i);
                if (latest != null && !latest.isEmpty()) {
                    return latest.length() <= maxChars ? latest : latest.substring(0, maxChars);
                }
            }
            return "";
        }

        // ② 按原时间顺序输出——不重排，模型看到的时间线不能颠倒
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < observations.size(); i++) {
            String observation = observations.get(i);
            if (observation != null) {
                sb.append(observation).append('\n');
            }
        }
        return sb.toString();
    }
}
