# Agent 任务队列方案（v3 迭代记录）

> 两阶段架构：Phase 1（纯文本 AI 回复）→ AfterAiHook 决策 → Phase 2（TaskPlanner 规划执行）

---

## 一、整体流程

```
User Input
    │
    ▼
AiServiceImpl.chatWithToolcall()
    │
    ├─ PromptHookChain（前置校验：注入检测、敏感词、速率限制）
    │   BLOCK → 返回错误
    │   REPLACE → 替换用户输入
    │   PASS → 继续
    │
    ▼
Phase 1: AI 纯文本调用（不带工具）
    ChatClient 未注册 defaultToolCallbacks
    → log: [Phase1] AI 初次回复
    │
    ▼
AfterAiHookChain（后处理判断）
    │
    ├─ BLOCK  → AiResponseRouter → 推送错误
    ├─ REPLACE → AiResponseRouter → 推送替换文本
    ├─ PASS   → AiResponseRouter → 推送原始回复
    │
    └─ PLANNING → AiResponseRouter → TaskPlanner
                          │
                          ▼
            Phase 2: TaskPlanner.planAndExecuteAsync()
                          │
                    ┌─────┴─────┐
                    │ 每轮循环    │  MAX_ROUNDS = 5
                    │            │
                    │ ① decompose()
                    │   askAiForPlan()  → AI 返回 JSON 工具列表
                    │   validatePlan()  → 三层校验
                    │   空计划 → 直接返回，终止
                    │   非空   → 强制追加 LLM_REASON
                    │
                    │ ② executeAll()
                    │   TaskExecutor 串行执行 TOOL_CALL
                    │   最后执行 LLM_REASON 聚合
                    │
                    │ ③ merge()
                    │   取 LLM_REASON 结论
                    │   推送 progressEvent("merging", "结论生成完成")
                    │
                    └──────────────┘
```

### 关键变更（从 v2 到 v3）

| 变更 | v2 | v3 |
|------|----|----|
| **第一轮 AI 是否带工具** | 是（defaultToolCallbacks） | **否**（纯文本，工具全归 Planner） |
| **去重机制** | markAiCompletedTools（关键词匹配，脆弱） | **已移除**——无重复可能 |
| **decompose()** | 关键词/n-gram 匹配 | AI 规划 + Java 三层校验 |
| **JSON 构建** | 手动字符串拼接 | ObjectMapper + SseUtils builder 方法 |
| **日志** | 分散无格式 | 带阶段标记 `[Phase1]` `[Round N]` `[规划]` `[执行]` |
| **异常处理** | 直接抛 raw error | 3 次重试 + 喂错误给 AI 生成友好回复 |

---

## 二、Phase 1：AI 纯文本调用

### 2.1 AiServiceImpl.chatWithToolcall()

```java
// 第一次 AI 调用——纯文本，不带工具
ChatClientRequestSpec prompt = chatClient.prompt()
        .user(currentContent);   // 没有 .tools() / .toolContext()
String result = prompt.call().content();
log.info("[Phase1] AI 初次回复, result={}", result);

// 后处理
HookResult afterResult = afterAiHookChain.execute(finalContent, result, ctx);
responseRouter.route(afterResult, finalContent, result, ctx, emitter);
```

注意：`ChatClient` 构建时没有 `.defaultToolCallbacks()`，所以 Phase 1 的 AI 调用不会执行任何工具。

### 2.2 重试机制

```java
int maxAttempts = 3;
for (int attempt = 1; attempt <= maxAttempts; attempt++) {
    try {
        // 调 AI
    } catch (Exception e) {
        if (attempt < maxAttempts) {
            // 将异常信息注入 prompt，让 AI 重试
            currentContent = finalContent + "\n\n[系统提示] ...失败，请重试：" + e.getMessage();
        }
    }
}
// 所有重试耗尽 → 友好提示
String friendlyMsg = "抱歉，AI 服务暂时不可用（" + error + "），请稍后再试。";
```

---

## 三、Phase 2：TaskPlanner 规划执行

### 3.1 日志格式（带阶段标记）

```
========== [Round 1] ① 规划拆解 ==========
  [规划] AI 建议: [{"tool":"queryTotalShops","params":{}}]
  [规划] 需执行 [tool=queryTotalShops, params={}]
  [规划] 追加 LLM_REASON
========== [Round 1] ② 执行子任务 ==========
    TOOL_CALL ✅ [tool=queryTotalShops]
    LLM_REASON ✅
========== [Round 1] ③ 聚合结论 ==========
========== [Round 2] ① 规划拆解 ==========
  [规划] AI 建议: []
========== [Round 2] ② 无需执行, 保持原回复 ==========
```

### 3.2 decompose() — AI 规划 + Java 校验

**askAiForPlan()**：调 AI 返回 JSON 数组 `[{"tool":"xxx","params":{...}}]`

上下文长度控制：已完成工具只传 50 字摘要 + description，不传完整 result。

**validatePlan() 三层校验**：
1. JSON 语法（parseable? isArray?）
2. 工具存在性（在 ToolBeanCollector 中注册过?）
3. 历史状态（已完成/终极失败?）

校验通过后**强制追加 LLM_REASON**——确保工具结果不被原样拼接，而是经 AI 总结后呈现。

**空计划兜底**：如果所有 AI 推荐的工具都被跳过（不存在/已完成/已失败），返回空列表，主循环立即退出，保持原 AI 回复。

### 3.3 TaskExecutor — 串行执行

```
executeAll():
  while (!queue.isAllDone()) {
    取所有 READY 任务
    串行执行（TOOL_CALL → LLM_REASON）
  }

executeTool():
  通过 toolName 匹配 ToolCallback
  调用 callback.call(jsonArgs, toolContext)
  成功 → markDone / 失败 → markFailed

executeLlmReason():
  收集已完成 TOOL_CALL 的结果
  注入失败任务摘要（如果存在）
  调 ChatClient 做最后聚合
```

---

## 四、SSE 事件协议

### 4.1 事件类型

所有 SSE 事件通过 SseUtils 构建（ObjectMapper 序列化，杜绝手动拼接）：

| 类型 | 方法 | JSON 示例 |
|------|------|-----------|
| 错误 | `errorEvent(msg)` | `{"error":"xxx","code":5001}` |
| 进度 | `progressEvent(stage, text)` | `{"type":"progress","stage":"planning","text":"..."}` |
| 工具步骤 | `stepEvent(toolName, status)` | `{"type":"progress","stage":"step","toolName":"q","status":"RUNNING"}` |
| 确认 | `confirmEvent(text)` | `{"type":"progress","stage":"confirm","text":"需要确认"}` |
| 元数据 | `metaEvent(conversationId)` | `{"type":"meta","conversationId":"..."}` |

### 4.2 推送序列

```
Phase 1 直接 PASS:
  → 原始 AI 回复（纯文本字符串）
  → SSE 完成

Phase 1 → PLANNING:
  → progress("planning", "规划完成：需要执行 xx")
  → step("xx", "RUNNING")
  → step("xx", "COMPLETED")
  → progress("merging", "正在生成结论...")
  → progress("merging", "结论生成完成")
  → 最终结论（纯文本字符串）
  → SSE 完成

异常：
  → errorEvent("友好提示")
  → SSE 完成
```

---

## 五、Hook 链

### 5.1 PromptHookChain（前置校验）

在 AI 调用前执行，返回 BLOCK / REPLACE / PASS。
当前已注册的 Hook：`InjectionDetectHook`、`SensitiveWordHook`。

日志级别未特别配置时继承 `com.hmdp: WARN`。
可通过 application.yaml 指定：

```yaml
com.hmdp.agent.hook: DEBUG    # 查看每个 Hook 的决策日志（Hook 链在 agent.hook 包）
```

### 5.2 AfterAiHookChain（后处理决策）

在 Phase 1 之后执行，按优先级短路：**BLOCK > REPLACE > PLANNING > PASS**。
当前已注册的 Hook：`TaskTriggerHook`（触发词检测）。

```java
// TaskTriggerHook 触发词列表
private static final List<String> TRIGGERS = List.of(
    "对比", "总结", "分析", "统计", "归纳", "报告",
    "比较", "差异", "变化", "趋势", "分别"
);
```

---

## 六、日志配置

### logback-spring.xml（简化版）

只有一个异步文件 Appender + 控制台，**无任何 per-package logger 定义**。所有日志级别由 application.yaml 的 `logging.level` 控制。

### application.yaml

```yaml
logging:
  level:
    com.hmdp: WARN                     # 其他 hmdp 包安静
    com.hmdp.agent: DEBUG              # AI Agent 主链路（含守卫/规划/Hook 子域）
    com.hmdp.agent.tool: DEBUG         # 工具调用细节
```

---

## 七、异常处理

| 场景 | 处理方式 |
|------|---------|
| Phase 1 AI 调用异常 | 最多 3 次重试，每次将错误注入 prompt，最后给友好提示 |
| TOOL_CALL 执行失败 | `markFailed`，LLM_REASON 聚合时自动注入失败摘要 |
| 全部工具失败 | LLM_REASON 仍会执行，通知用户部分数据不可用 |
| 死锁（就绪队列空但任务未完成） | `executeAll` 检测到死锁直接 break |

---

## 八、工程稳定性

### 线程池

| 名称 | 核心/最大 | 队列 | 用途 |
|------|-----------|------|------|
| `aiTaskExecutor` | 2 / 4 | 100 | Phase 1 AI 异步调用 |
| `subtaskExecutor` | 10 / 50 | 200 | Phase 2 TaskPlanner 规划执行 |

### 测试工具

StatsQueryTool 提供测试用统计工具：
- `queryTotalShops` → 返回店铺总数
- `queryTotalUsers` → 返回注册用户数
- `queryTotalBlogs` → 返回博客总数

---

## 九、文件清单

| 文件 | 说明 |
|------|------|
| `hook/PromptHook.java` | 前置 Hook 接口 |
| `hook/PromptHookChain.java` | 前置链式执行器（Fail-Open） |
| `hook/AfterAiHook.java` | 后处理 Hook 接口 |
| `hook/AfterAiHookChain.java` | 后处理链式执行器（优先级短路） |
| `hook/HookResult.java` | 决策结果（PASS/BLOCK/REPLACE/PLANNING） |
| `hook/impl/TaskTriggerHook.java` | 触发词检测（≤15 行） |
| `agent/response/AiResponseRouter.java` | 后处理路由器 |
| `agent/task/model/SubTask.java` | 子任务数据模型 |
| `agent/task/TaskType.java` | 枚举：TOOL_CALL / LLM_REASON |
| `agent/task/SubTaskStatus.java` | 枚举：PENDING / READY / RUNNING / COMPLETED / FAILED |
| `agent/task/TaskQueue.java` | 任务队列（终结状态检查） |
| `agent/task/TaskReport.java` | 执行报告（含终极失败黑名单） |
| `agent/task/TaskExecutor.java` | 串行执行器 |
| `agent/task/TaskPlanner.java` | 规划器（循环版） |
| `agent/task/TaskSnapshot.java` | 任务快照（CONFIRM 续跑） |
| `agent/util/SseUtils.java` | JSON 事件构建 + SSE 推送封装 |
| `agent/config/AgentConfig.java` | ChatClient（无默认工具）、线程池 |
| `agent/service/impl/AiServiceImpl.java` | 两阶段编排入口 |
