# Agent 模块 — 简历项目亮点

> **用途**: 面试自我介绍用  
> **版本**: v5.0 (2026-08-11)  
> **风格**: 务实工程叙事，非 PPT 包装  
> **本版新增**: Agent 成本治理（工具路由按需加载 + 上下文 token 滚雪球根治）

---

## 项目职责与难点攻克

**LLM Agent 智能对话模块** — 在 Spring AI + DashScope 基础上，为电商系统接入自然语言交互能力，让 AI 能安全地查询/操作业务数据，并具备可观测、可续聊、**成本受控**的生产级能力。

---

**难点 1：AI 调接口不失控** — 聊天场景下，AI 可能生成恶意指令（如"删库"、"批量发帖"）或进入穷举循环。我的做法是在 Agent 执行链上设**两道闸口**：

- **前置闸口（输入阶段）**：PromptHookChain 输入 Hook 链做 Prompt 注入检测（本地关键词 + 正则，Redis 仅辅助限流，Redis 异常降级为本地阈值，不阻塞对话主流程）；命中规划类意图（查/统计/天气等宽触发词）时转两阶段规划。
- **执行闸口（工具调用阶段）**：GuardedToolCallback 统一包装 ToolCallback，过 4 个 Policy（高危名单、需确认名单、正则匹配、Redis 限流），任一拒绝即拦截；工具方法上的 `@RequiredDataPermission` AOP 切面做数据归属兜底校验。

上线至今未发生工具越权操作。

---

**难点 2：避免工具重复执行与局部故障卡死，并产出干净的最终答案** — 最初 AI 一次回复内会多次尝试调工具，且某个工具失败后整轮中断。重构为**两阶段规划（TaskPlanner）**：

- **决策**：Phase1 轻量判定是否进入规划——不调工具直接回答的场景不走规划；
- **规划**：AI 把任务分解为 JSON 工具调用序列，经**三层校验**（JSON 语法 → 工具存在 → 历史状态）生成带依赖的子任务队列，异步执行；
- **合并**：子任务全部结束后由 LLM 把中间结果**合并成一段最终答案**——历史会话只落这段最终答案，中间的规划草稿/子任务过程不落库，用户看到的是干净回复；
- 执行中引入"终结状态"，部分工具失败不阻塞全链路，`[Round N]` 阶段日志方便追溯。

---

**难点 3：真流式 + 阶段进度** — 早期是"伪流式"（阻塞等完整结果再推）。改为**直连 `DashScopeChatModel.stream()`** 逐 token 推送（绕过 ChatClient 层，避免其观察对象污染 Reactor context 导致 traceId 断链），配合 `ObservedSseEmitter` 管理生命周期；SSE 用 meta/progress/error 三类事件推任务阶段（规划中/合并中/需确认），前端实时展示进度而非干等。

---

**难点 4：异步线程丢失登录上下文** — Spring AI 的工具回调在独立线程执行，`ThreadLocal` 拿不到用户信息。解决：发起调用时用 `.toolContext(Map.of("userId", userId))` 把用户 ID 显式传进去，工具方法加 `ToolContext` 参数接收，不走 `UserHolder`。麻烦在于需要确保团队写的每个新工具都遵守这个约定，不能漏传。

---

**难点 5：全链路可观测（TraceId 串树）** — AI 链路多异步多线程，一次对话产生 `session→phase1→plan→subagent→tool_call→guard` 多层 span。最初**各 span 各占一个 traceId**，Langfuse 里串不起整棵树。两个根因（字节码 + 实测实锤）：

1. **生命周期时序错误**：`AgentTracer.start()` 原顺序 createNotStarted→openScope→start，micrometer 在 onStart 时读到已被塞入的**空 TracingContext**，父级被短路 → 全部变新 trace 根。改为**先 `start()` 后 `openScope()`** 修复。
2. **Reactor context 污染**：ChatClient 层观察对象写进 Reactor context，model 层读父读到它但无 TracingContext → 新 traceId。改为流式**直连 model 层 + `contextWrite` 显式传父**。

修复后全链路同 traceId。另做了**观测白名单**（`ObservabilityTraceFilter` 只放行 `agent.`/`spring.ai.`/`gen_ai.` 前缀），否则 Boot 的 `@Scheduled` 任务（每 2s）观测会把云观测配额吃光（实测 21.6 万 units/月）。Langfuse 云版不展示自定义 span attributes，业务语义改用 **span 名编码**承载（`agent.{类型}.{语义}`）。

---

**难点 6：业务历史会话** — Spring AI 的 `chat_memory` 表无 `user_id`、schema 私有，不可按业务查询。自建 `agent_conversation` + `agent_message` 两张业务表：

- 按 `(conversation_id, user_id)` 归属校验，会话列表 + 点进续聊，跨用户不泄露存在性；
- SSE 回合**按 AfterAiHook 决策分支落库**（PASS/REPLACE 落、BLOCK 不落）；PLANNING 回合在 TaskPlanner 完成点落**最终合并答案**（不是 Phase1 开场草稿）；
- 落库走 **best-effort**（try/catch），失败只记日志不影响对话主链路——避免 SSE 重试循环因持久化异常重跑 LLM 浪费 token。

---

**难点 7：工具多了之后 Guard 包装容易遗漏** — 手动注册 + 手动包 Guard 全靠人工记，新人容易漏掉守卫导致安全漏洞。我写了一个 `@TargetTool` 注解，启动时自动扫描带此注解的 Bean，通过 `ToolCallbacks.from()` 批量注册，同时自动用 Guard 包装，让安全守卫成为不可跳过的流程。核心收益是**安全不依赖人肉记忆**。

---

**难点 8：工具多了、上下文滚雪球 → 成本失控** — 工具从 7 个扩到 17 个后，规划阶段**全量加载所有工具提示词**（每 key 一次同步 HTTP 拉取 ~0.12s，串行累加），子 agent 执行时工具结果**全量回灌消息历史、每轮重发**——实测一轮三次调用的输入 token 为 `1,001 → 3,045 → 5,131` 逐轮翻倍，输入/输出比一度到 12:1。两手治理：

- **工具路由（按需加载提示词）**：规划 prompt 从"全量工具名+完整描述"压成「工具名 + 短标签 + 参数名」紧凑目录，规划 LLM **一次调用内完成"选工具 + 定参数"**，不加额外 LLM 调用；识别不出时输出 `__UNCERTAIN__` 标记，Java 检测到后用全量目录重跑一次（保底，每轮最多一次）。选型时否决 RAG（对当前工具量太重）与关键词匹配（语义泛化差），最终选**轻量 LLM 路由 + 目录压缩**。实测规划阶段工具提示词加载从全量降为只读相关的 2 个。配套做了 `PromptCacheWarmer` 启动并行预拉全部提示词（Langfuse 外置首拉 ~0.12s×N 只发生在启动期），并借工具扩容实现"店铺类型 → 店铺 → 优惠券 → 我的订单""用户 → 博客 → 详情/评论"两类长任务串链。
- **上下文滚雪球根治**：子 agent 从 Spring AI 内置 tool-call 循环（每轮把全部历史重发）改为**手动工具循环 + LLM 结果压缩**——每步工具结果由一次独立微调用压成 ≤80 字要点摘要才入历史，**原始结果不进上下文，累积量 O(1) 不随工具数/轮数增长**；框架层 `GuardedToolCallback` 对任何工具结果硬截断（1200 字符、codepoint-safe 防 emoji 截断乱码）兜底未知工具；工具层返回紧凑 DTO（`BlogBrief` top-5 + `total` 字段保住"共几篇"的总量语义）；`currentResponse` 截 400 字。**预计**子 agent 累计输入 token 从 ~9.2k 降到 ~3.9k（约 57% 削减），且上下文不再随轮次滚雪球（设计目标，部署后验证中）。
