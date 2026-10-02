package com.hailin.blogsystem.service.impl;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 候选 B 的**离线形状检查**（零成本）。
 *
 * <p>B 是新写的 prompt，它有两个"不用花额度就能锁住"的量：
 * <ol>
 *   <li><b>第一段多大</b>——纯闲聊请求只走第一段，B 对闲聊类的收益全押在这一段的大小上；</li>
 *   <li><b>域表与装载表是否一致</b>——prompt 里列了某个域、装载表却没有它，会静默退化成
 *       "只装公共块"，而模型照样输出 JSON，是最难从结果反推的那类错误。</li>
 * </ol>
 */
class CandidateBShapeTests {

    @Test
    void routePromptStaysSmall() {
        assertThat(CandidateB.ROUTE_PROMPT.length())
                .as("路由段字符数——纯闲聊只付这一段的钱，它必须远小于现状 25626 字符的全量 prompt")
                .isLessThan(3000);
    }

    @Test
    void everyDomainIsListedInRoutePrompt() {
        for (String domain : CandidateB.DOMAINS) {
            assertThat(CandidateB.ROUTE_PROMPT)
                    .as("路由 prompt 里必须列出域：" + domain + "（否则模型永远不会输出它，装载表那一项就是死代码）")
                    .contains(domain);
        }
    }

    /**
     * 路由段的输出能被 Jackson 解析成 {@link CandidateB.RouteResult}。
     *
     * <p>这是一次调用就要花额度的路径，低级错误（record 组件名对不上、多目标数组解析不了）
     * 应该在离线就红，而不是跑完 26 条才发现"每次路由都解析失败 → 全部降级"。
     */
    @Test
    void routeResultParses() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper()
                .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        CandidateB.RouteResult single = mapper.readValue("""
                {"candidateDomains":["CHAT"],"requestKind":"CHAT","confidence":0.98,
                 "needsThinking":false,"needsThinkingReason":null,"reason":"纯知识问答"}
                """, CandidateB.RouteResult.class);
        assertThat(single.candidateDomains()).containsExactly("CHAT");
        assertThat(single.requestKind()).isEqualTo("CHAT");
        assertThat(single.confidence()).isEqualTo(0.98);

        // 多目标：这是 B 相对 A 的核心卖点，必须能一次拿到多个域
        CandidateB.RouteResult multi = mapper.readValue("""
                {"candidateDomains":["ARTICLE","ARTICLE_SEARCH"],"requestKind":"TASK","confidence":0.9,
                 "needsThinking":true,"needsThinkingReason":"依赖前文","reason":"点赞并找文章","extra":"忽略我"}
                """, CandidateB.RouteResult.class);
        assertThat(multi.candidateDomains()).containsExactly("ARTICLE", "ARTICLE_SEARCH");
        assertThat(multi.needsThinking()).isTrue();
    }

    @Test
    void extractorPromptSizes() throws Exception {
        List<List<String>> combos = List.of(
                List.of("CHAT"),
                List.of("ARTICLE"),
                List.of("ARTICLE", "ARTICLE_SEARCH"),
                List.of("ARTICLE", "NAVIGATE"),
                List.of("LEARNING"),
                List.of("OPTIMIZE_ARTICLE"),
                List.of("WALLET"),
                List.of("EDITOR"),
                List.of("CREATE_ARTICLE"));

        StringBuilder report = new StringBuilder("=== 候选 B：第二段（Extractor）prompt 字符数 ===\n");
        report.append("参考：现状 FULL = ").append(AiIntentClassifierImpl.buildSystemPrompt(false).length())
                .append(" 字符\n\n");
        report.append(String.format("%-26s %10s%n", "域组合", "字符数"));
        for (List<String> combo : combos) {
            report.append(String.format("%-26s %10d%n", String.join("+", combo),
                    CandidateB.extractorPrompt(combo).length()));
        }
        report.append("\n注：纯闲聊（CHAT）不会走到第二段——它由第一段直接收口。\n");

        Files.createDirectories(Path.of("target"));
        Files.writeString(Path.of("target", "candidate-b-shape.txt"), report.toString(), StandardCharsets.UTF_8);
        System.out.print(report);
    }
}
