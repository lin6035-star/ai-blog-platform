# V3.0 通用思考模式 · 阶段简短收口

> 日期：2026-09-01 ~ 09-02（实现 + Codex 交叉审查修复）
> 完整长清单：`docs/agent/agent-module-acceptance-checklist.md`（V1 → V3.0）
> 一句话：普通聊天接入 Agent Runtime——状态依赖型聊天先查再答，纯问答直达不变。

## 做了什么

```text
入口：AiIntent 加 needsThinking / needsThinkingReason
      语义判定（非词表）：默认 false、仅 GENERAL_CHAT 生效、reason 仅抽样排查
裁决：Planner 单点归一化 decideGeneralAgent（游客恒 false → 建议不一致降 CHAT → 进通用域）
      AiMessageServiceImpl 只消费 AgentDecision，双路径（主分发 + fallback）分支
执行：GeneralAgentRuntime 三件套
      白名单 {QUERY_MEMORY, SEARCH_RAG, ASK_USER, FINAL_ANSWER}（无建议桥、无写动作）
      QUERY_MEMORY 为主（V3 增量），SEARCH_RAG 只补证据
      基类 maxSteps() 钩子：通用域 3，学习/文章域默认 5 不变
延迟：maxSteps=3 上限 + Decider prompt「能不查就不查」下限，双闸门
落地：GENERAL_CHAT + needsThinking 分支直接 return → 天然跳过普通聊天自动 RAG（无双份检索）
```

## Codex 交叉审查与修复

| 问题 | 修复 |
|---|---|
| 前端 import：AgentWriteProposal 未用 / WorkflowType 缺 import（会挡构建） | 删一加一，vue-tsc 零错误 |
| editor-layout.spec.ts CRLF 换行硬匹配失败 | 读取 normalize `\r\n→\n`，断言语义不变 |
| IntegrationTests 默认跳过，needsThinking 真实模型判定未被证明 | 补 2 个真实 LLM 断言（状态依赖→true / 纯概念→false） |

V3 主线经 Codex 确认无缺陷：needsThinking 走线、Planner 单点消费、游客回落 CHAT、通用 Runtime 白名单干净。

## 验证状态

```text
后端：55 类 / 343 @Test 全绿（Agent 相关 42 类）
      真实 LLM 集成测试 3/3（LEARNING_ASSIST false / 状态依赖 true / 纯概念 false）
前端：vue-tsc 零错误 + 22 文件 / 97 测试全绿
E2E：七场景验收通过（进通用 Runtime / 纯问答直达 / 文章学习专线不变 / 寒暄 / 游客 / 思考中刷新）
边界修复：思考中刷新丢消息 → 占位「思考中…」+ 3s 轮询补拉
         游客明确诉求被 CTA 追问 → 硬边界前置登录提示 + 学习计划正则补漏
```

## 下一步

- V3 领域注册（前哨已验证通用化路径，三域可注册表化）
- vs 继续放开写权限（V2.4 只放了 UPDATE_TASK_DONE）；工程化补课可穿插
- 与 Codex 对齐后启动；needsThinking 漏拦抽样（reason 字段留痕）作为放宽闸门的量化依据
