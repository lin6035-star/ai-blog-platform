# 01myBlog AI / Agent 璺嚎

## 瀹氫綅锛氬叏绔欑粺涓€ AI Agent Shell

瀹冧笉鏄煇绡囨枃绔犺鎯呴〉閲岀殑鐙珛灏忓姪鎵嬶紝鑰屾槸璐┛棣栭〉銆佹枃绔犺鎯呴〉銆佷釜浜轰腑蹇冦€佺紪杈戝櫒绛夐〉闈㈢殑缁熶竴
AI 鍏ュ彛銆?
AI Agent 閫氳繃 PageContext 鎰熺煡鐢ㄦ埛褰撳墠鎵€鍦ㄩ〉闈紝骞堕€愭鎵╁睍 Context銆乀ool
Calling銆丷AG銆丮emory 鍜?Agent Workflow銆?
鏈€缁堢洰鏍囷細

> 鏋勫缓涓€涓?AI 澧炲己鍨嬩釜浜哄崥瀹㈢郴缁燂紝鑰屼笉鏄畝鍗曟帴鍏ヨ亰澶╂帴鍙ｃ€?
------------------------------------------------------------------------

## 褰撳墠杩涘害锛?026-08-27锛?
- V1 鍏ㄧ珯 AI 鍔╂墜鍩虹鐗?鉁咃紙娓稿娆℃暟闄愬埗鏈仛锛?- V2 SSE 娴佸紡杈撳嚭 鉁?- V3 RAG 鍗氬鐭ヨ瘑搴?鉁咃紙ES 鍚戦噺妫€绱?+ keyword 娣峰悎妫€绱?+ 瑙勫垯 rerank + references锛?- V4 Tool Calling 鉁咃紙鍩虹宸ュ叿闆嗭級
- V5 闀挎湡璁板繂 鉁咃紙姝ｅ紡璁板繂 + 鍊欓€夌‘璁?+ ES 鐩镐技鍙洖 + LLM 鏇存柊鍐崇瓥 + prompt 娉ㄥ叆锛?- V6 Agent Workflow / Router V1 鉁咃紙5 涓?Workflow + Planner 瑁佸喅 + active workflow + 鐢ㄦ埛纭/鍙嶉閲嶅啓锛?- V6 宸ョ▼鍖栫ǔ瀹氭€?鉁咃紙Step Log + FAILED/retry + Redis 閿?+ DB version + Idempotency-Key + 闄愭祦 + XSS 闃叉姢锛?- V7 Agent Runtime V1 / V1.5 鉁咃紙Learning Agent 鍙 Loop锛屽厛涓嶄笂 Multi-Agent / 妗嗘灦锛?  - 宸插啓姝讳笁鏉¤竟鐣岋紙2026-08-26锛夛細鈶?鏂版剰鍥?LEARNING_AGENT + 璺敱浼樺厛绾?activeWorkflow > ASSIST > PLAN/PROGRESS > AGENT > CHAT锛屽悗绔?甯垜+瀛︿範"瀹藉厹搴曚笉鍐嶇洿鎺ュ紑鍒跺畾璁″垝锛涒憽 Agent 鍐崇瓥 prompt 涓嶈嚜鍔ㄦ敞鍏ヨ蹇嗭紝璁板繂鍙蛋鏄惧紡 QUERY_MEMORY锛涒憿 涓嶆暣鍖呭鐢?AiLearningDashboardTool锛堝畠娣疯璁″垝+璁板繂锛夛紝Executor 鐩存帴璋冨悇 Service锛屾瘡 action 鐙珛 observation
- V8 Agent Runtime V2.1 鉁咃紙2026-08-28锛夛細Agent 鍩轰簬 observation 寤鸿鍚姩瀛︿範绫?Workflow锛圫UGGEST_WORKFLOW锛夛紝蹇呴』鐢ㄦ埛纭锛圵AITING_WORKFLOW_CONFIRM 纭鍗?+ confirm/cancel 鎺ュ彛锛夛紝涓嶅紑鏀剧洿鎺ュ啓鍔ㄤ綔锛涘惈闆惰瀵熻鍒?/ 鐘舵€?CAS 骞跺彂淇濇姢 / 骞傜瓑 / 杩囨湡娓呯悊 / 鍒锋柊鎭㈠锛涚‘璁ゅ悗澶嶇敤鐜版湁 Workflow Runtime銆傝璁★細`docs/agent/agent-runtime-v2-1-workflow-suggestion-design.md`
- V9 Agent Runtime V2.2 + V2.3 鉁咃紙2026-08-28锛夛細V2.2 inspection 鍙 API锛坮un 璇︽儏 / steps / 浼氳瘽鍘嗗彶鍒嗛〉锛屽彧鏌ユ湰浜猴紝瀹夊叏鎽樿锛夛紱V2.3 鎬濊€冭繃绋嬪疄鏃跺睍绀猴紙AGENT_STEP 浜嬩欢 + 鎶樺彔闈㈡澘 + 鍒锋柊鎭㈠ + 鍏叡鏂囨 AgentStepLabelSupport 淇濊瘉鍒锋柊鍓嶅悗涓€鑷达級锛泂treamLearningAgent 鏀?Flux.create + boundedElastic 瀹炴椂鎺ㄦ祦锛堜笌 Workflow 鍒涘缓鍚屾ā寮忥級
- V10 Agent Runtime V2.5 鉁咃紙2026-09-01锛夛細绗簩涓崟 Agent锛堟枃绔犲煙锛夈€傛娊 AbstractAgentRuntime 鍏叡楠ㄦ灦锛堝惊鐜?缁堟€?钀藉簱/杩囨湡娓呯悊/寤鸿妗ワ紝棰嗗煙宸紓閽╁瓙鍖栵級+ 鍏叡 SSE 绠￠亾 streamAgentReply锛堝涔?鏂囩珷鍏辩敤锛? ArticleAgentRuntime锛圦UERY_ARTICLE 鏌ュ簱楠屽綊灞?+ 缁撴瀯鎽樿锛? 鏂囩珷渚у缓璁ˉ锛圓RTICLE_AGENT 鈫?寤鸿鍗?鈫?confirm 澶嶇敤 OPTIMIZE_ARTICLE锛? 缁堝眬澶辫触璇箟锛堝綊灞炲け璐?1 姝ユ敹灏撅紝涓嶆硠闇?ID锛夛紱琛屼负璇箟锛氬挩璇㈠瀷鐩存帴缁欐剰瑙併€佽鍔ㄥ瀷寤鸿鍗°€佹槑纭寚浠ょ洿杈撅紱绔埌绔簲鍦烘櫙楠屾敹閫氳繃
- V11 Agent Runtime V3.0 鉁咃紙2026-09-01锛夛細閫氱敤鎬濊€冩ā寮忊€斺€旀櫘閫氳亰澶╂帴杩?Agent Runtime銆侫iIntent 鍔?needsThinking/needsThinkingReason锛堜笌 intent 姝ｄ氦锛歩ntent 绠″煙銆乶eedsThinking 绠¤繘涓嶈繘閫氱敤 Runtime锛涜涔夊垽瀹氶潪璇嶈〃銆侀粯璁?false銆佷粎 GENERAL_CHAT 鐢熸晥锛? GeneralAgentRuntime 涓変欢濂楋紙鐧藉悕鍗?4 鍔ㄤ綔鏃犲缓璁ˉ銆乵axSteps=3 鍩虹被閽╁瓙鍖栥€丵UERY_MEMORY 涓轰富 / SEARCH_RAG 琛ヨ瘉鎹€併€岃兘涓嶆煡灏变笉鏌ャ€峱rompt 鍙岄椄闂級+ Planner 鍗曠偣褰掍竴鍖栵紙decideGeneralAgent锛欳TA 淇濇寔 鈫?寤鸿涓嶄竴鑷撮檷 CHAT 鈫?娓稿鎭?false 鈫?杩涢€氱敤鍩燂紱AiMessageServiceImpl 鍙秷璐?AgentDecision锛屽弻璺緞鍒嗘敮澶╃劧璺宠繃鑷姩 RAG锛? 杈圭晫淇锛堟€濊€冧腑鍒锋柊涓㈡秷鎭?鈫?鍓嶇鍗犱綅+3s 杞琛ユ媺锛涙父瀹㈡槑纭瘔姹傝 CTA 杩介棶 鈫?isExplicitGuestBusinessIntent 鍓嶇疆鐧诲綍鎻愮ず + 瀛︿範璁″垝姝ｅ垯琛ユ紡锛夛紱绔埌绔竷鍦烘櫙楠屾敹閫氳繃
- V12 Agent Runtime V3.1 鉁咃紙2026-09-02锛夛細鍙楁帶鍐欐墿灞曗€斺€旂 2 涓啓鍔ㄤ綔 ADD_LEARNING_TASK锛堝悜璁″垝闃舵杩藉姞鐢ㄦ埛鐐瑰悕鐨勬柊浠诲姟锛屼粛璧版彁妗堚啋纭鍗♀啋confirm鈫扖AS+蹇収锛夈€傚弻灞?actionType 璇箟鍐欐锛堝灞?SUGGEST_WRITE + 鍐呭眰 input.actionType锛岀己鐪佸洖钀?UPDATE_TASK_DONE锛汚gentWriteProposal.TYPE_* 鍏叡甯搁噺闃插瓧闈㈤噺婕傜Щ锛? 鐩爣瑙ｆ瀽鎷嗗垎锛坮esolvePlanAndStage 璁″垝鈫掗樁娈?/ resolveTaskIndex 浠?UPDATE鈥斺€擜DD 鐨?taskTitle 鏄柊浠诲姟涓嶅仛瀛樺湪鏍￠獙锛孋odex 璇勫淇锛? 閲嶅棰勬鏄庣‘鎷掔粷锛堜笉渚濊禆 appendTasks 闈欓粯鍘婚噸锛? 鍒嗙被鍣ㄥ垎鐣岋紙鐪熷疄 LLM 楠岃瘉涓彂鐜般€屽姞浠诲姟銆嶈鍒?LEARNING_ASSIST鈥斺€斾慨姝ｄ负锛氱敤鎴风偣鍚嶄换鍔″悕=鍙楁帶鍐?/ 鏈粰浠诲姟鍚嶈 AI 鐢熸垚=Workflow锛? repair prompt 琛?SUGGEST_WRITE + 鍓嶇纭鍗℃寜 actionType 娓叉煋锛堝師鎸?done 涓夌洰浼氶敊鏄撅級锛涙祴璇?69 鍗曟祴 + 鐪熷疄 LLM 4/4 鍏ㄧ豢
- V12.1 V3.1 E2E 淇杞?鉁咃紙2026-09-02~03锛夛細鍥涜繛淇€斺€斺憼 assist/progress 璁″垝瀹氫綅锛氬缓璁ˉ confirm 琛ユ秷鎭偣鍚嶏紙鍘熷彧鎸?userId 瑙ｆ瀽锛屽璁″垝鐢ㄦ埛鐐瑰悕涔熷繀琚姹傞噸閫夛級锛涒憽 闃舵瀹氫綅锛氭爣棰樹负涓婚鍚嶆椂銆岄樁娈典笁銆? order_num 3 搴忓彿鐩磋В锛堟爣棰樺垎璇嶄粎 fallback锛? 搴忓彿褰掍竴鐢ㄤ腑鏂囷紙闃挎媺浼暟瀛?token 浼氳鎾炰换鍔℃爣棰樿涔夋暟瀛楀銆? 涓?3 浠庛€嶏紝瀹炴祴鎷嗚В閿欒惤闃舵浜旓級锛涒憿 瀛︿範绫?workflow 鍒濆閾捐矾瀹炴椂姝ラ浜嬩欢锛堜笁澶勫垱寤烘祦 emitter 鍘熶负绌哄疄鐜?鈫?鍓嶇杞湀 30s~2min 鏃犲崱锛屼笌鏂囩珷绫诲榻?WORKFLOW_STEP + 鍓嶇涓存椂鍗￠浂鏀瑰姩锛夛紱鈶?鍒嗙被鍣?JSON repair锛堣В鏋愬け璐?repair prompt 閲嶈瘯涓€娆″啀闄嶇骇鈥斺€斿琛屾秷鎭?闀夸换鍔″悕鏇捐嚧妯″瀷鎶婅鍒欐枃瀛楁贩杩?JSON锛岀偣鍚嶅崟浠诲姟璇夋眰闈欓粯閫€鍖栨櫘閫氳亰澶╋級+ 閲嶅浠诲姟鎻愭鍓嶉妫€涓嶅脊鍗?+ isTerminalExtension 寰幆璞佸厤锛堟墿灞曠粓鎬佽鎷掑悗涓嶅啀琚櫘閫氭墽琛岋級锛涙祴璇?64 鍗曟祴 + 鐪熷疄 LLM 8/8 鍏ㄧ豢

涓嬩竴姝ョ粏鑺傦細

- Agent 妯″潡闃舵鏀跺彛锛圴1鈫扸3.1锛夛細`docs/agent/agent-module-acceptance-checklist.md`
- V3.0 绠€鐭敹鍙ｏ紙鍚?Codex 浜ゅ弶瀹℃煡淇锛夛細`docs/agent/agent-v3-0-general-thinking-closeout.md`
- V3.1 璁捐锛堝彈鎺у啓鎵╁睍 ADD_LEARNING_TASK锛夛細`docs/agent/agent-v3-1-controlled-write-add-task-design.md`
- Agent V1 楠屾敹锛歚docs/agent/agent-v1-acceptance-checklist.md`
- Agent Runtime V1 璁捐锛歚docs/agent/agent-runtime-v1-design.md`
- Agent Runtime V2.1 璁捐锛歚docs/agent/agent-runtime-v2-1-workflow-suggestion-design.md`
- Workflow 鍘嗗彶涓庤竟鐣岋細`docs/workflow/agent-workflow-next-stage-notes.md`

------------------------------------------------------------------------

# V1锛氬叏灞€ AI Assistant 鍩虹鐗?鉁?
鐩爣锛氬厛鎶?AI 鍔╂墜瀹屾暣璺戣捣鏉ワ紝褰㈡垚缁熶竴鍏ュ彛銆?
鍔熻兘锛堥櫎鏍囨敞澶栧潎宸茶惤鍦帮級锛?
-   鍏ㄧ珯鍙充晶鎮诞 AI 鎸夐挳 鉁?-   鐐瑰嚮灞曞紑 AI 灏忕獥 / 鎶藉眽 鉁?-   鏅€氳亰澶?鉁咃紙宸插崌绾т负 SSE 娴佸紡锛?-   鍓嶇鍙戦€?PageContext 鉁?-   AI 璇嗗埆褰撳墠椤甸潰 鉁?-   鏂囩珷璇︽儏椤佃鍙栧綋鍓嶆枃绔犲唴瀹瑰洖绛旈棶棰?鉁?-   棣栭〉銆佷釜浜轰腑蹇冦€佺紪杈戝櫒鎻愪緵椤甸潰鎰熺煡 鉁?-   娓稿涓存椂浼氳瘽闄愬埗娆℃暟锛堝緟鍋氾級
-   鐧诲綍鐢ㄦ埛鎸佷箙浼氳瘽 鉁?-   鐧诲綍鐢ㄦ埛鍙柊寤恒€佹煡鐪嬨€佸垹闄ゅ巻鍙蹭細璇?鉁?
## PageContext

绀轰緥锛?
``` json
{

# 历史版本简记（V1~V6，细节已由 agent-module-acceptance-checklist.md 与 git 历史完整覆盖）

- V1 全局 AI 助手：全站统一入口 + PageContext 页面感知 + 游客/登录会话（游客次数限制暂缓）
- V2 SSE 流式输出：逐字渲染 / 停止 / 重试 / 自动标题
- V3 RAG 博客知识库：文章切片 + Embedding + ES 向量检索 + 来源引用
- V4 Tool Calling：文章/用户/收藏/点赞等查询 + 标题/摘要/标签生成 + 草稿联动
- V5 长期记忆：正式/候选双表 + 用户确认 + ES 相似召回 + LLM 决策 + prompt 注入
- V6 Agent Workflow：5 个 Workflow（AI 写作 / 学习计划 / 进度调整 / 难点攻坚 / 文章优化）+ 确认反馈闭环 + Step Log / retry / 失败恢复
- 学习点：Embedding/RAG/ES、Function Calling、Memory 设计、Workflow 状态机、Human-in-the-loop、Observability
鍗氬涓氬姟鎻愪緵鏁版嵁鍜岃兘鍔涳紝AI璐熻矗鐞嗚В銆佽皟鐢ㄥ拰缁勭粐銆?