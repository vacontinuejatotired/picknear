# Agent 模块 — 简历项目亮点

> **用途**: 面试自我介绍用  
> **版本**: v4.0 (2026-08-05)  
> **风格**: 务实工程叙事，非 PPT 包装

---

## 项目职责与难点攻克

**LLM Agent 智能对话模块** — 在 Spring AI + DashScope 基础上，为电商系统接入自然语言交互能力，让 AI 能安全地查询/操作业务数据，并具备可观测、可续聊的生产级能力。

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
