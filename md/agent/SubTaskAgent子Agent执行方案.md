# SubTaskAgent — 子 Agent 执行方案

> **版本**: v2.1  
> **创建日期**: 2026-07-24  
> **基于**: [Agent任务队列方案](./Agent任务队列方案.md) v3 的 Phase 2 改造  
> **相关文档**: [Agent模块架构设计](./Agent模块架构设计.md), [Agent模块发展路线图](./Agent模块发展路线图.md)  
> **对应代码路径**: 
> - `picknear/src/main/java/com/hmdp/agent/subagent/` — 子 Agent 新增组件
> - `picknear/src/main/java/com/hmdp/agent/task/` — TaskPlanner 等改造
> - `picknear/src/main/java/com/hmdp/agent/config/` — 配置类

---

## 目录

1. [背景与动机](#1-背景与动机)
2. [设计目标](#2-设计目标)
3. [整体架构](#3-整体架构)
4. [详细设计](#4-详细设计)
    - [4.1 新增数据模型](#41-新增数据模型)
    - [4.2 SubTaskAgent 组件](#42-subtaskagent-组件)
    - [4.3 subAgentChatClient Bean](#43-subagentchatclient-bean)
    - [4.4 配置化（超时、重试、功能开关）](#44-配置化超时重试功能开关)
    - [4.5 TaskPlanner 改造](#45-taskplanner-改造)
    - [4.6 子 Agent Prompt 设计](#46-子-agent-prompt-设计)
    - [4.7 SSE 事件协议变更](#47-sse-事件协议变更)
5. [安全与错误处理](#5-安全与错误处理)
6. [边界情况](#6-边界情况)
7. [文件清单与迁移计划](#7-文件清单与迁移计划)

---

## 1. 背景与动机

### 1.1 当前架构的局限

现有 Phase 2 执行链路（v3）：

```
TaskPlanner.decompose() → TaskQueue → TaskExecutor.executeAll() → merge()
                                ↑
                        串行同步直调 ToolCallback
                        + 最后 LLM_REASON 聚合
```

核心问题：

| 问题 | 现状 | 后果 |
|------|------|------|
| **执行机械** | `TaskExecutor.executeTool()` 直接调 `callback.call(jsonArgs)`，结果存原始 JSON | 摘要全靠后续 LLM_REASON 补，两次 LLM 调用之间上下文割裂 |
| **摘要被动** | LLM_REASON 只是"拼接结果 → 让 LLM 总结"，没有理解执行过程中的语义 | 无法处理"工具 A 失败了，改用工具 B 的数据"这类智能降级 |
| **串行瓶颈** | `TaskExecutor.executeAll()` 串行遍历 TaskQueue | 无依赖的工具无法并行执行 |
| **状态管理重** | TaskQueue + SubTaskStatus 状态机 + TaskReport 历史，三层状态维护 | 增加了代码复杂度和出错可能 |

### 1.2 为什么使用子 Agent

将"工具执行 + 结果理解"合并为一个**独立的、带工具的 LLM 调用**（即子 Agent），收益：

- **语义执行**：子 Agent 理解每个工具做什么，可以智能决定参数、理解返回
- **一次摘要**：执行完所有工具后直接生成自然语言摘要，无需额外 LLM_REASON 阶段
- **天然并行**：若底层模型/框架支持并行 tool call，无依赖工具可一次执行
- **安全复用**：工具调用仍然经过 GuardedToolCallback，安全防线不变

---

## 2. 设计目标

### 2.1 核心目标

| # | 目标 | 衡量标准 |
|---|------|---------|
| 1 | 工具执行阶段由子 Agent 驱动 | `TaskExecutor` 不再直接调 `callback.call()` |
| 2 | 子 Agent 返回结构化摘要（含原始数据快照） | 摘要 + 可解析的 JSON 数据快照 |
| 3 | 对主 Agent 透明 | `TaskPlanner` 拿到 `SubTaskResult.summary` 直接当聚合结果 |
| 4 | 保留现有安全体系 | GuardedToolCallback + Permission AOP 不变 |
| 5 | 可回退 | `feature.subagent.enabled=false` 时走原 TaskExecutor 逻辑 |

### 2.2 非目标（明确不做）

- 不改变 Phase 1 流程（AiServiceImpl 保持不变）
- 不改变 decompose() 的 AI 规划 + Java 三层校验
- 不替换多轮循环（MAX_ROUNDS = 5 不变）
- 不引入异步消息队列（子 Agent 仍同步返回，运行在 subtaskExecutor 上）

---

## 3. 整体架构

### 3.1 改造前后对比

**改造前（v3）：**

```
Phase 2:
  TaskPlanner.loop()
    ├─ decompose()       → List<SubTask>
    ├─ TaskQueue         → 状态机管理
    ├─ TaskExecutor      → 串行调用 ToolCallback
    │   ├─ executeTool()     → callback.call(jsonArgs) → 原始 JSON
    │   └─ executeLlmReason() → ChatClient 聚合 → 摘要
    └─ merge()           → 取 LLM_REASON 结果
```

**改造后（v4）：**

```
Phase 2:
  TaskPlanner.loop()
    ├─ decompose()               → List<SubTask> (仅 TOOL_CALL)
    ├─ [feature.subagent.enabled]  → 配置开关
    │   ├─ true  → SubTaskAgent   → 带工具的 ChatClient
    │   └─ false → TaskExecutor   → 原有串行直调（回退）
    └─ SubTaskResult.summary       → 作为本轮聚合结果
```

### 3.2 新增组件关系

```
┌──────────────────────────────────────────────────┐
│              application.yml                     │
│  agent.subtask.timeout=30s                       │
│  agent.subtask.max-retries=3                     │
│  agent.subtask.retry-backoff=1s                  │
│  feature.subagent.enabled=true                   │
└──────────┬───────────────────────────────────────┘
           │ @ConfigurationProperties
           ▼
┌──────────────────────┐    ┌──────────────────────┐
│  SubTaskProperties   │    │  FeatureProperties   │
└──────┬───────────────┘    └──────┬───────────────┘
       │                          │
       ▼                          ▼
┌──────────────────────────────────────────────────────┐
│                  SubTaskAgent                        │
│  - 接收 SubTaskPlan                                   │
│  - 根据 plan.tasks 动态筛选 ToolCallback[]             │
│  - 调 subAgentChatClient（带筛选后工具）                │
│  - 从回复中解析 JSON 数据快照 → SubTaskResult          │
│  - 带退避的重试 + 结构化日志                             │
└──────────────────────┬───────────────────────────────┘
                       │ 使用
                       ▼
┌──────────────────────────────────────────────────────┐
│                  TaskPlanner                         │
│  - 判断 feature.subagent.enabled                     │
│  - 构造 SubTaskPlan → 调 SubTaskAgent.execute()     │
│    └─ 或 回退 → TaskExecutor.executeAll()            │
│  - SubTaskResult.summary → currentResponse           │
└──────────────────────────────────────────────────────┘
```

---

## 4. 详细设计

### 4.1 新增数据模型

#### 4.1.1 SubTaskPlan

```java
// 位于 agent/subagent/ 包下
package com.hmdp.agent.subagent;

import lombok.Builder;
import lombok.Data;
import java.util.List;
import java.util.Map;

/**
 * 子 Agent 执行计划。
 * <p>
 * 由 TaskPlanner 在每轮循环中构造，
 * 传给 SubTaskAgent.execute() 作为输入。
 * </p>
 */
@Data
@Builder
public class SubTaskPlan {

    /** 原始用户输入 */
    private String userInput;

    /** Phase 1 或上一轮的 AI 回复（当前累积回复） */
    private String currentResponse;

    /** 本轮规划的 TOOL_CALL 子任务列表 */
    private List<SubTask> tasks;

    /** 历史摘要（key=toolName, value=50字摘要） */
    private Map<String, String> historySummary;

    /** 当前用户 ID */
    private Long userId;

    /** 当前轮次（0-based） */
    private int round;
}
```

设计说明：

- 传给子 Agent 的**不是**完整历史 result（防 Token 爆炸），而是经过 `truncate(50)` 的摘要。
- `currentResponse` 是截至目前的最佳回复，子 Agent 在此之上做增量工具执行。
- `round` 用于日志和 SSE 进度标识。

#### 4.1.2 SubTaskResult

```java
// 位于 agent/subagent/ 包下
package com.hmdp.agent.subagent;

import lombok.Builder;
import lombok.Data;
import java.util.List;
import java.util.Map;

/**
 * 子 Agent 执行结果。
 * <p>
 * SubTaskAgent.execute() 的返回值。
 * summary 字段直接作为 TaskPlanner 本轮聚合结果。
 * rawResults 由子 Agent Prompt 强制附加的 JSON 数据快照解析而来。
 * </p>
 */
@Data
@Builder
public class SubTaskResult {

    /** 自然语言摘要（直接作为本轮 currentResponse，不含 JSON 快照） */
    private String summary;

    /** 工具执行结果原始数据（key=toolName, value=结果）
     *  来源：子 Agent 回复末尾 ===DATA_SNAPSHOT=== 段的 JSON 解析 */
    private Map<String, Object> rawResults;

    /** 工具执行错误信息（key=toolName, value=错误消息） */
    private Map<String, String> errors;

    /** 是否全部工具执行成功 */
    private boolean allSuccess;

    /** 实际执行的 toolName 列表（按执行顺序） */
    private List<String> executedTools;

    /** 子 Agent 调用耗时（ms） */
    private long executionTimeMs;
}
```

设计说明：

- `summary` 是剥离 JSON 快照后的纯文本摘要。
- `rawResults` 从 LLM 回复末尾的 `===DATA_SNAPSHOT===` 段解析（详见 4.6 节 prompt 设计）。
- `errors` 从快照中 status=error 的条目提取。
- `rawResults` 中每个 value 会被截断至 **RAW_DATA_MAX_LENGTH = 500 字符**（`parseResult()` 中执行），防止 Token 爆炸。
  - 注意：500 字仅对 `rawResults` 中的 data 字段生效；自然语言摘要部分（summary）不受此限制。

#### 4.1.3 TaskType 变更

```java
public enum TaskType {
    TOOL_CALL,
    @Deprecated LLM_REASON  // 保留兼容，不再使用
}
```

`LLM_REASON` 标记为 `@Deprecated`，因摘要职责已由子 Agent 承担。保留枚举值是为了兼容已有日志和监控。

`TaskPlanner.validatePlan()` 中对应的**强制追加 LLM_REASON 逻辑删除**。

---

### 4.2 SubTaskAgent 组件

#### 4.2.1 类设计

```java
// 位于 agent/subagent/ 包下
package com.hmdp.agent.subagent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.agent.task.SubTask;
import com.hmdp.agent.tool.ToolBeanCollector;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 子任务执行 Agent。
 * <p>
 * 职责：接收 SubTaskPlan → 按 tasks 筛选 ToolCallback →
 * 调带工具的 ChatClient → 从回复中提取 JSON 数据快照 → 返回 SubTaskResult。
 * 运行在 subtaskExecutor 线程池上，不阻塞主 SSE 线程。
 * </p>
 */
@Slf4j
@Component
public class SubTaskAgent {

    @Resource
    @Qualifier("subAgentChatClient")
    private ChatClient subAgentChatClient;

    @Resource
    private ToolBeanCollector toolBeanCollector;

    @Resource
    private SubTaskProperties properties;

    private static final ObjectMapper JSON = new ObjectMapper();

    /** rawResults 中单个 data 值的最大字符数（超长截断，防 Token 爆炸） */
    private static final int RAW_DATA_MAX_LENGTH = 500;

    /**
     * 执行子任务计划，返回摘要。
     */
    public SubTaskResult execute(SubTaskPlan plan) {
        long start = System.currentTimeMillis();
        List<SubTask> tasks = plan.getTasks();
        List<String> toolNames = tasks.stream()
                .map(SubTask::getToolName)
                .distinct()
                .toList();

        // ═════ 入口结构化日志 ═════
        log.info("[SubAgent] 开始执行 [round={}, taskCount={}, tasks={}, userId={}]",
                plan.getRound(), tasks.size(), toolNames, plan.getUserId());

        // 1. 按 plan.tasks 筛选工具（同名工具去重，只保留一个 ToolCallback 实例）
        ToolCallback[] filteredCallbacks = filterCallbacks(toolNames);
        if (filteredCallbacks.length == 0) {
            log.warn("[SubAgent] 无可用的 ToolCallback [round={}, requested={}]",
                    plan.getRound(), toolNames);
            return SubTaskResult.builder()
                    .summary(plan.getCurrentResponse())
                    .allSuccess(false)
                    .errors(Map.of("subAgent", "无可用的工具"))
                    .executionTimeMs(System.currentTimeMillis() - start)
                    .build();
        }

        // 2. 构建执行 Prompt
        String prompt = buildExecutionPrompt(plan);

        // 3. 带退避重试调用（含总超时保护）
        String content = executeWithRetry(prompt, filteredCallbacks,
                properties.getMaxRetries(), properties.getRetryBackoff(),
                properties.getTotalTimeout(), start);

        if (content == null) {
            // 全部重试耗尽或总超时
            long elapsed = System.currentTimeMillis() - start;
            log.warn("[SubAgent] 执行失败（重试耗尽或超时） [round={}, elapsed={}ms]",
                    plan.getRound(), elapsed);
            return SubTaskResult.builder()
                    .summary("⚠️ 服务暂时不可用，请稍后重试。")
                    .allSuccess(false)
                    .errors(Map.of("subAgent", "重试耗尽"))
                    .executionTimeMs(elapsed)
                    .build();
        }

        // 4. 解析结果（从回复中提取 JSON 数据快照 + 数据截断 + 降级兜底）
        SubTaskResult result = parseResult(content, start);

        // ═════ 出口结构化日志（只打 key 列表，不打全量数据） ═════
        log.info("[SubAgent] 执行完成 [round={}, elapsed={}ms, allSuccess={}, toolResults={}]",
                plan.getRound(), result.getExecutionTimeMs(), result.isAllSuccess(),
                result.getRawResults() != null ? result.getRawResults().keySet() : "none");

        return result;
    }

    /**
     * 根据任务规划中的 toolName 筛选 ToolCallback。
     * 防止子 Agent 调用本轮未规划的工具（如写操作工具）。
     */
    private ToolCallback[] filterCallbacks(List<String> toolNames) {
        ToolCallback[] all = toolBeanCollector.getToolCallbacks();
        if (all == null) return new ToolCallback[0];
        Set<String> allowed = new HashSet<>(toolNames);
        return Arrays.stream(all)
                .filter(cb -> allowed.contains(cb.getToolDefinition().name()))
                .toArray(ToolCallback[]::new);
    }

    /**
     * 带指数退避和总超时控制的重试调用。
     * retryBackoff 为基础间隔，每次翻倍：1s → 2s → 4s
     * totalTimeout 为整个 execute() 的总超时（含重试），超时直接终止。
     */
    private String executeWithRetry(String prompt, ToolCallback[] callbacks,
                                     int maxRetries, Duration retryBackoff,
                                     Duration totalTimeout, long roundStartMs) {
        Exception lastError = null;
        String currentPrompt = prompt;

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            // 总超时检查
            if (System.currentTimeMillis() - roundStartMs > totalTimeout.toMillis()) {
                log.warn("[SubAgent] 执行总超时 [attempt={}/{}, elapsed>{}ms]",
                        attempt, maxRetries, totalTimeout.toMillis());
                break;
            }

            try {
                String content = subAgentChatClient.prompt()
                        .user(currentPrompt)
                        .tools(callbacks)            // ← 动态传入筛选后的工具
                        .call()
                        .content();
                log.info("[SubAgent] 调用成功 [attempt={}/{}]", attempt, maxRetries);
                return content;
            } catch (Exception e) {
                lastError = e;
                log.warn("[SubAgent] 调用失败 [attempt={}/{}], err={}",
                        attempt, maxRetries, e.getMessage());

                if (attempt < maxRetries) {
                    // 指数退避
                    long backoffMs = retryBackoff.toMillis() * (long) Math.pow(2, attempt - 1);
                    log.info("[SubAgent] {}ms 后重试...", backoffMs);
                    try {
                        Thread.sleep(backoffMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    // 标准化错误注入格式
                    currentPrompt = prompt + "\n\n[系统提示] 上一次调用失败，原因：" + e.getMessage() + "。请重试。";
                }
            }
        }
        log.error("[SubAgent] 重试耗尽 [maxRetries={}]", maxRetries, lastError);
        return null;
    }

    /**
     * 从 LLM 回复中提取 JSON 数据快照。
     * <p>
     * 子 Agent 的 Prompt 强制要求回复末尾附加：
     * ===DATA_SNAPSHOT===
     * { "toolName": {"status":"ok","data":...} }
     * ===DATA_SNAPSHOT_END===
     * </p>
     *
     * 降级策略：
     * - LLM 未附加 JSON 快照 → rawResults={}，summary 取完整 content
     * - JSON 解析失败 → rawResults={}，摘要不变，日志记录警告
     * - data 字段超长（>RAW_DATA_MAX_LENGTH）→ 截断
     */
    private SubTaskResult parseResult(String content, long start) {
        long elapsed = System.currentTimeMillis() - start;

        // 提取 JSON 快照
        String snapshotStr = extractSnapshot(content);
        Map<String, Object> rawResults = new LinkedHashMap<>();
        Map<String, String> errors = new LinkedHashMap<>();
        boolean allSuccess = true;
        List<String> executedTools = new ArrayList<>();

        if (snapshotStr != null) {
            try {
                Map<String, Object> snapshot = JSON.readValue(snapshotStr,
                        new TypeReference<Map<String, Object>>() {});
                for (Map.Entry<String, Object> entry : snapshot.entrySet()) {
                    executedTools.add(entry.getKey());
                    // value 可能是 {"status":"ok","data":...} 或 {"status":"error","message":...}
                    if (entry.getValue() instanceof Map<?, ?> detail) {
                        String status = Objects.toString(detail.get("status"), "");
                        if ("error".equals(status)) {
                            allSuccess = false;
                            errors.put(entry.getKey(),
                                    Objects.toString(detail.get("message"), "未知错误"));
                        }
                        // 对 data 字段做 500 字符截断，防 Token 爆炸
                        Object data = detail.get("data");
                        if (data instanceof String s && s.length() > RAW_DATA_MAX_LENGTH) {
                            data = s.substring(0, RAW_DATA_MAX_LENGTH) + "...(截断)";
                        }
                        rawResults.put(entry.getKey(), data != null ? data : detail);
                    } else {
                        rawResults.put(entry.getKey(), entry.getValue());
                    }
                }
            } catch (Exception e) {
                log.warn("[SubAgent] JSON 快照解析失败, 将使用完整回复作为摘要 [err={}]", e.getMessage());
                // 解析失败不阻断，content 全文作为 summary
            }
        } else {
            log.warn("[SubAgent] 未检测到 JSON 快照标记, rawResults 将为空");
        }

        // 摘要 = 去除 JSON 快照部分后的纯文本；无快照时全文作为摘要
        String summary = snapshotStr != null
                ? content.substring(0, content.indexOf("===DATA_SNAPSHOT===")).trim()
                : content;

        return SubTaskResult.builder()
                .summary(summary)
                .rawResults(rawResults.isEmpty() ? null : rawResults)
                .errors(errors.isEmpty() ? null : errors)
                .allSuccess(allSuccess)
                .executedTools(executedTools)
                .executionTimeMs(elapsed)
                .build();
    }

    /** 提取 ===DATA_SNAPSHOT=== ... ===DATA_SNAPSHOT_END=== 之间的 JSON */
    private String extractSnapshot(String content) {
        if (content == null) return null;
        int startIdx = content.indexOf("===DATA_SNAPSHOT===");
        if (startIdx < 0) return null;
        startIdx += "===DATA_SNAPSHOT===".length();
        int endIdx = content.indexOf("===DATA_SNAPSHOT_END===", startIdx);
        if (endIdx < 0) return null;
        return content.substring(startIdx, endIdx).trim();
    }

    private String buildExecutionPrompt(SubTaskPlan plan) {
        // 详见 4.6 节
        return ExecutionPromptBuilder.build(plan);
    }
}
```

#### 4.2.2 关键设计说明

| 机制 | 说明 |
|------|------|
| **工具动态筛选** | 每轮只传入 `plan.tasks` 中列出的工具（同名工具去重），防止子 Agent 误调其他工具 |
| **JSON 快照解析** | 强制子 Agent 在回复末尾附加结构化数据，`parseResult()` 从中提取 `rawResults` |
| **全量数据截断** | rawResults 中每个 data 值截断至 500 字符（`RAW_DATA_MAX_LENGTH`），防 Token 爆炸 |
| **快照降级** | LLM 未附加 JSON 快照时 rawResults={}，摘要取完整回复，不阻断流程 |
| **指数退避重试** | 基础间隔 1s → 2s → 4s，最大 3 次，标准化错误注入格式 |
| **双重超时** | 单次 LLM 调用超时（默认 30s）+ 整个 execute() 总超时（默认 60s） |
| **结构化日志** | 入口和出口各一条，出口只打 key 列表不打全量数据，防日志爆炸 |

---

### 4.3 subAgentChatClient Bean

在 `AgentConfig.java` 中新增：

```java
/**
 * 子 Agent ChatClient（带工具）。
 * <p>
 * 与主 aliibabaChatClient 的区别：
 * - 主 Client：Phase 1 纯文本回复，不绑工具
 * - 子 Agent Client：Phase 2 工具执行，运行时动态绑定筛选后的工具（通过 .tools(filtered)）
 * </p>
 */
@Bean("subAgentChatClient")
public ChatClient subAgentChatClient(DashScopeChatModel chatModel) {
    // 注意：不在这里 .defaultTools() 绑定全部工具，
    // 而是在 SubTaskAgent.execute() 中按 plan.tasks 动态 .tools(filteredCallbacks)
    return ChatClient.builder(chatModel)
            .defaultSystem("""
                    你是任务执行助手，负责调用工具获取数据并汇总结果。

                    核心职责：
                    1. 根据任务描述，调用合适的工具获取数据
                    2. 理解工具返回的数据
                    3. 用中文汇总成一段完整的回答

                    规则：
                    - 每次只调用一个工具，等待返回结果后再调下一个
                    - 工具参数必须严格遵守下方给出的约束，不得修改
                    - 工具返回空数据时，如实说明"暂无数据"
                    - 工具调用失败时，在摘要中说明原因，继续执行其他工具
                    - 所有工具执行完毕后，用中文给出完整回答
                    - 在回复末尾必须附加 JSON 数据快照（格式见用户 prompt）
                    """)
            .build();
}
```

**动态传工具而非 defaultTools**：

```java
// 在 SubTaskAgent.executeWithRetry() 中：
subAgentChatClient.prompt()
    .user(prompt)
    .tools(filteredCallbacks)   // ← 动态传入，每轮不同
    .call()
    .content();
```

原因：

- `defaultTools()` 在 Bean 创建时固定，无法按需筛选。
- `prompt().tools(callbacks)` 在每次调用时传入，SubTaskAgent 可以根据 `plan.tasks` 动态决定哪些工具可见。
- 避免子 Agent 误调用本轮不需要的工具（如写操作工具）。

---

### 4.4 配置化（超时、重试、功能开关）

#### 4.4.1 SubTaskProperties

```java
package com.hmdp.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 子 Agent 执行配置。
 * <p>
 * 配置项前缀：agent.subtask
 * </p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "agent.subtask")
public class SubTaskProperties {

    /** 子 Agent 单次 LLM 调用超时 */
    private Duration timeout = Duration.ofSeconds(30);

    /** 整个 execute() 总超时（含重试在内），超时直接终止 */
    private Duration totalTimeout = Duration.ofSeconds(60);

    /** 最大重试次数（含首次调用） */
    private int maxRetries = 3;

    /** 重试基础退避间隔（指数增长：1s → 2s → 4s） */
    private Duration retryBackoff = Duration.ofSeconds(1);
}
```

#### 4.4.2 FeatureProperties

```java
package com.hmdp.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 功能开关配置。
 * <p>
 * 配置项前缀：feature
 * </p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "feature")
public class FeatureProperties {

    /** 子 Agent 功能开关 */
    private SubAgent subagent = new SubAgent();

    @Data
    public static class SubAgent {
        /** true=使用 SubTaskAgent；false=走原 TaskExecutor 串行直调 */
        private boolean enabled = true;
    }
}
```

#### 4.4.3 application.yml 配置

```yaml
agent:
  subtask:
    timeout: 30s             # 子 Agent 单次 LLM 调用超时
    total-timeout: 60s       # 整个 execute() 总超时（含重试）
    max-retries: 3           # 最大重试次数
    retry-backoff: 1s        # 重试基础退避间隔

feature:
  subagent:
    enabled: true            # 子 Agent 功能开关（false 时走原 TaskExecutor）
```

---

### 4.5 TaskPlanner 改造

#### 4.5.1 主循环变更

```java
// === 改造前（v3） ===
// Round ② 执行子任务
TaskQueue queue = new TaskQueue(tasks);
TaskExecutor executor = new TaskExecutor(toolCallbacks, ctx.getUserId(),
        chatClient, TASK_TIMEOUT_MS);
executor.executeAll(queue);

// SSE：逐任务推送完成状态
for (SubTask t : queue.getAllTasks()) {
    if (t.getToolName() == null) continue;
    String st = t.getStatus() == SubTaskStatus.COMPLETED ? "COMPLETED" : "FAILED";
    SseUtils.safeSend(emitter, SseUtils.stepEvent(t.getToolName(), st));
}

// Round ③ 聚合结论
currentResponse = merge(currentResponse, queue);

// === 改造后（v4） ===
@Resource
private SubTaskAgent subTaskAgent;

@Resource
private FeatureProperties featureProperties;

// Round ②+③ 执行 + 聚合
if (featureProperties.getSubagent().isEnabled()) {
    // ── 子 Agent 路径 ──
    SubTaskPlan plan = SubTaskPlan.builder()
            .userInput(input)
            .currentResponse(currentResponse)
            .tasks(tasks)
            .historySummary(buildHistorySummary(history))
            .userId(ctx.getUserId())
            .round(round)
            .build();

    // SSE：告知前端开始执行
    SseUtils.safeSend(emitter, SseUtils.progressEvent("executing",
            "正在执行 " + tasks.size() + " 个任务..."));

    SubTaskResult result = subTaskAgent.execute(plan);

    // SSE：数据汇总完成（parseResult 已执行），即将生成最终回答
    SseUtils.safeSend(emitter, SseUtils.progressEvent("merging",
            "数据汇总完成，生成回答..."));

    currentResponse = result.getSummary();
    recordHistory(history, result);
} else {
    // ── 回退路径：完整恢复 v3 TaskExecutor 行为 ──
    TaskQueue queue = new TaskQueue(tasks);
    TaskExecutor executor = new TaskExecutor(toolCallbacks, ctx.getUserId(),
            chatClient, TASK_TIMEOUT_MS);
    executor.executeAll(queue);

    for (SubTask t : queue.getAllTasks()) {
        if (t.getToolName() == null) continue;
        String st = t.getStatus() == SubTaskStatus.COMPLETED ? "COMPLETED" : "FAILED";
        SseUtils.safeSend(emitter, SseUtils.stepEvent(t.getToolName(), st));
    }
    // 回退路径仍需 merge() 做 LLM_REASON 聚合
    // 注：LLM_REASON 枚举值保留，TaskExecutor.executeLlmReason() 依然正常工作
    currentResponse = merge(currentResponse, queue);
}
```

#### 4.5.2 删除的代码

| 方法 | 说明 | 替代 |
|------|------|------|
| `validatePlan()` 中强制追加 LLM_REASON | 不再需要子 Agent 自行摘要 | 删除 |

注意：`TaskPlanner.merge()` **保留不删**，因回退模式（`feature.subagent.enabled=false`）仍需要它做 LLM_REASON 聚合。

#### 4.5.3 保留的代码

| 文件/方法 | 说明 |
|-----------|------|
| `TaskPlanner.decompose()` | AI 规划 + Java 三层校验不变 |
| `TaskPlanner.askAiForPlan()` | 规划 prompt 微调（参考 4.6 节） |
| `TaskPlanner.validatePlan()` | 三层校验保留，仅删除 LLM_REASON 追加 |
| `TaskReport.java` | 历史记录保留 |
| `SubTask.java` | 数据模型保留 |
| `SubTaskStatus.java` | 保留供日志/监控使用 |

#### 4.5.4 TaskExecutor / TaskQueue 保留

> ⚠️ **后续演进（P5 重整，`93c59a0`）**：原方案标注 `@Deprecated` 归档；2026-08 用户拍板
> "保留回退=重整"——**已移除 @Deprecated**（feature 开关下是活路径，非死代码），javadoc 改为
> 回退路径定位，包结构收拢为 `legacy.task`（与 `legacy.plan` 并列）。

`TaskExecutor.java` 和 `TaskQueue.java` **不删除**，作为 `feature.subagent.enabled=false` 的回退路径保留。

```java
// 回退路径活组件（P5 重整后无 @Deprecated）
public class TaskExecutor { ... }

public class TaskQueue { ... }
```

`feature.subagent.enabled=false` 时完全恢复 v3 行为：

| v3 组件 | 回退模式状态 |
|---------|-------------|
| `TaskExecutor.executeAll()` | 使用 |
| `TaskQueue` | 使用 |
| `TaskPlanner.merge()` | 使用（保持原样） |
| `TaskExecutor.executeLlmReason()` | 使用（LLM_REASON 枚举仍保留） |
| 强制追加 LLM_REASON | 保留（在 validatePlan() 回退分支中恢复） |

#### 4.5.5 CONFIRM 续跑适配

```java
public void resumeFromSnapshot(TaskSnapshot snapshot, SseEmitter emitter) {
    // 快照恢复 → 构造 SubTaskPlan → SubTaskAgent 重新执行
    // 不需要恢复 TaskQueue，因为子 Agent 每次独立执行
}
```

---

### 4.6 子 Agent Prompt 设计

#### 4.6.1 执行 Prompt（buildExecutionPrompt）

```java
// ExecutionPromptBuilder.java
public class ExecutionPromptBuilder {

    public static String build(SubTaskPlan plan) {
        return """
你需要根据以下任务计划，调用工具获取数据，并给用户一段完整的中文回答。

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
【上下文】
用户问题：%s

AI 已有回复：%s

历史执行摘要（已完成工具的结果摘要）：
%s

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
【参数约束—你无权修改参数值】

%s

以下是你必须严格执行的工具参数。这些参数的值已由系统设定，你无权修改，调用工具时必须原样使用。

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
【本轮待执行任务】
%s

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
要求：
1. 逐个调用所需工具，每次调一个，拿到结果后再调下一个
2. 如果工具返回了数据，理解数据含义并纳入回答
3. 如果工具失败，在回答中说明原因，继续执行其他工具
4. 所有工具执行完毕后，用中文给出对用户的完整回答
5. 不要编造工具没有返回的数据

【重要—回复格式要求】
在所有工具执行完毕后、中文回答之后，在回复末尾附加以下 JSON 数据快照（不要 markdown 代码块标记）：

===DATA_SNAPSHOT===
{
  "toolName1": {
    "status": "ok",
    "data": <工具返回的主要数据，不超过500字>
  },
  "toolName2": {
    "status": "error",
    "message": "错误描述"
  }
}
===DATA_SNAPSHOT_END===

注意：data 字段的值请控制在 500 字以内，超出部分会被系统自动截断。
""".formatted(
                plan.getUserInput(),
                plan.getCurrentResponse(),
                buildHistorySummaryText(plan.getHistorySummary()),
                buildParamConstraints(plan.getTasks()),
                buildTasksDesc(plan.getTasks())
        );
    }
}
```

其中 `tasksDesc` 格式：

```
任务 1: 查询博客列表（queryBlog）
  参数：userId = 1001

任务 2: 查询天气（queryWeather）
  参数：city = 北京
```

**参数约束段** — 放在 tasksDesc 之前，刚性约束防止 LLM 篡改参数：

```
参数约束（你无权修改参数值，调用时原样使用）：
  工具 queryBlog：
    userId 的值已由系统设定为 "1001"，你无权修改该值，调用时原样使用
  工具 queryWeather：
    city 的值已由系统设定为 "北京"，你无权修改该值，调用时原样使用
```

#### 4.6.2 规划 Prompt 微调（askAiForPlan）

原有规划 prompt 基本不变，只调整：

```diff
- - 不需要额外工具则输出 []
+ - 不需要额外工具则输出空数组 []
```

不再需要规划 prompt 关心"是否需要总结"——总结由子 Agent 自动完成。

---

### 4.7 SSE 事件协议变更

#### 4.7.1 变更对照

| 阶段 | v3（改造前） | v4（改造后） | 原因 |
|------|-------------|-------------|------|
| 规划完成 | `progress("planning", "规划完成：需要执行 xx、yy")` | 不变 | — |
| 工具开始 | `step(xx, "RUNNING")` → `step(yy, "RUNNING")` | **删除** | 子 Agent 内部执行，前端不可知 |
| 工具结束 | `step(xx, "COMPLETED")` → `step(yy, "FAILED")` | **删除** | 同上 |
| **执行中** | 无 | **新增** `progress("executing", "正在执行 N 个任务...")` | 子 Agent 调用前推送 |
| **汇总中** | 无 | **新增** `progress("merging", "数据汇总完成，生成回答...")` | parseResult 完成后推送 |
| 合并完成 | `progress("merging", "结论生成完成")` | **删除**（被"汇总中"替代） | — |

#### 4.7.2 最终 SSE 事件序列

```
Phase 1 → PLANNING:
  → progress("planning", "规划完成：需要执行查询博客、查询天气")
  → progress("executing", "正在执行 2 个任务...")        ← 子 Agent 调用前
  → progress("merging", "数据汇总完成，生成回答...")      ← parseResult 完成后
  → 子 Agent 摘要文本（纯文本，不含 JSON 快照）
  → SSE 完成

异常：
  → progress("error", "xxx")
  → SSE 完成
```

#### 4.7.3 SubTaskResult 到 SSE 的映射

```java
// TaskPlanner 中推送结果
SubTaskResult result = subTaskAgent.execute(plan);

if (!result.isAllSuccess() && result.getErrors() != null && !result.getErrors().isEmpty()) {
    // 有工具失败 → 推送警告（不阻断）
    String errorSummary = result.getErrors().entrySet().stream()
            .map(e -> e.getKey() + ": " + e.getValue())
            .collect(Collectors.joining("；"));
    SseUtils.safeSend(emitter, SseUtils.stepEvent("warning", errorSummary));
}

SseUtils.safeSend(emitter, SseUtils.progressEvent("merging", "结论生成完成"));
```

---

## 5. 安全与错误处理

### 5.1 安全防线（不变）

```
subAgentChatClient
    │
    ▼
ChatClient.call() → LLM 返回 tool call 请求
    │
    ▼
Spring AI Tool Callback 机制
    │
    ▼
GuardedToolCallback.call()  ← 安全守卫：ToolGuardManager 投票
    │
    ▼
@Tool → ToolPermissionAspect  ← AOP 权限校验
    │
    ▼
实际业务方法
```

| 安全层 | 状态 | 说明 |
|--------|------|------|
| ToolGuardManager 投票 | 不变 | GuardedToolCallback 内嵌 |
| Permission AOP | 不变 | 切面在 @Tool 方法上 |
| PromptHookChain | 不变 | Phase 1 前置校验 |
| **工具动态筛选** | **新增** | SubTaskAgent 只传 plan.tasks 指定的工具 |

**子 Agent 不需要独立的 PromptHookChain**：
1. 子 Agent 的输入是 `SubTaskPlan`，由 Java 代码构造（非用户直接输入）
2. 用户输入已在 Phase 1 经过 PromptHookChain 校验
3. 工具调用层的安全由 GuardedToolCallback + Permission AOP 兜底

### 5.2 错误处理矩阵

| 场景 | 处理方式 |
|------|---------|
| **子 Agent LLM 调用失败** | 指数退避重试（1s→2s→4s），最多 3 次，每次注入错误到 prompt；耗尽返回降级摘要 |
| **子 Agent LLM 超时** | `agent.subtask.timeout` 控制（默认 30s），超时抛异常 → 进入重试 |
| **工具调用失败** | LLM 收到工具返回的异常，JSON 快照中 status=error，继续执行其他工具 |
| **全部工具失败** | 子 Agent 摘要："查询时遇到问题：xx不可用、yy不可用"；`allSuccess=false` |
| **部分工具失败** | 摘要混合成功和失败信息；SSE 推送 `step("warning", ...)` |
| **工具返回为空** | 子 Agent 提示"暂无数据"，不影响其他工具 |
| **子 Agent 返回空摘要** | 保持原有的 `currentResponse` 不变 |
| **JSON 快照解析失败** | 不退梗，content 全文作为 summary，rawResults 为空 |
| **工具动态筛选后为空** | 直接返回 `currentResponse`，`allSuccess=false` |

### 5.3 超时控制

| 层级 | 超时 | 控制方式 |
|------|------|---------|
| 子 Agent 单次 LLM 调用 | `agent.subtask.timeout`（默认 30s） | ChatClient 超时配置 + `@ConfigurationProperties` |
| 重试退避 | `agent.subtask.retry-backoff`（默认 1s） | 指数增长，`Thread.sleep()` |
| 整个 planAndExecute | 5 轮 × (decompose + execute) | subtaskExecutor 自行控制 |
| SSE 连接 | 浏览器默认 | 每轮 safeSend 保活 |

**关于 subtaskExecutor 线程池**：`executeWithRetry()` 中的 `Thread.sleep()` 会占用线程池中的线程。为确保退避期间不阻塞其他任务的执行，`subtaskExecutor` 的 `corePoolSize` 建议 ≥ 5。

当前配置（参考 `AgentConfig.java`）：

```java
// subtaskExecutor 现有配置
executor.setCorePoolSize(10);   // 当前 core=10，≥5，满足要求
executor.setMaxPoolSize(50);
executor.setQueueCapacity(200);
```

现有 core=10 已满足退避占用要求，**无需修改**。

---

## 6. 边界情况

### 6.1 空任务列表

`decompose()` 返回空列表 → 直接 return，不调 SubTaskAgent：

```java
List<SubTask> tasks = decompose(...);
if (tasks.isEmpty()) {
    log.warn("无需执行，保持原回复");
    return currentResponse;
}
```

与现有行为一致。

### 6.2 只有失败工具

所有工具都已在前几轮终态失败（finalFailed）→ `decompose` 返回空 → 同 6.1。

### 6.3 单个工具任务

一轮只规划了一个 TOOL_CALL，子 Agent 同样执行——一次 LLM 调用 + 一次 tool call。开销略大于直调，但换来统一的摘要品质。

### 6.4 子 Agent 全部重试耗尽

返回降级 `SubTaskResult`：

```json
{
  "summary": "⚠️ 服务暂时不可用，请稍后重试。",
  "allSuccess": false,
  "errors": {"subAgent": "重试耗尽"}
}
```

TaskPlanner 用该摘要作为最终回复，结束 SSE。

### 6.5 JSON 快照解析失败

LLM 未按要求附加 `===DATA_SNAPSHOT===` 段 → `extractSnapshot()` 返回 null → `summary` 取完整 content → `rawResults` 为空。日志记录警告，不抛异常：

```java
log.warn("[SubAgent] JSON 快照解析失败, 将使用完整回复作为摘要");
```

### 6.6 功能开关关闭

`feature.subagent.enabled=false` → TaskPlanner 走回退路径，使用原有的 `TaskExecutor.executeAll()`。此时 `SubTaskAgent`、`subAgentChatClient` 等组件仍存在但不会被调用。

### 6.7 多轮衔接

- 第 N 轮子 Agent 完成后，`currentResponse = result.summary`
- 第 N+1 轮 `decompose()` 时，将前几轮的 `historySummary`（来自 `result.rawResults`，每条 50 字截断）传给规划 AI

---

## 7. 文件清单与迁移计划

### 7.1 新增文件

| 文件 | 说明 |
|------|------|
| `agent/subagent/SubTaskAgent.java` | 子 Agent 执行器（含工具筛选、JSON 快照解析、退避重试） |
| `agent/subagent/SubTaskPlan.java` | 子 Agent 输入数据模型 |
| `agent/subagent/SubTaskResult.java` | 子 Agent 输出数据模型 |
| `agent/subagent/ExecutionPromptBuilder.java` | Prompt 构建（独立类，便于单测） |
| `agent/config/SubTaskProperties.java` | 子 Agent 配置（`agent.subtask.*`） |
| `agent/config/FeatureProperties.java` | 功能开关配置（`feature.subagent.*`） |

### 7.2 修改文件

| 文件 | 变更 |
|------|------|
| `agent/config/AgentConfig.java` | 新增 `subAgentChatClient` Bean（无 defaultTools） |
| `agent/task/TaskPlanner.java` | 主循环注入 SubTaskAgent + FeatureProperties；保留回退路径 |
| `agent/task/TaskType.java` | `LLM_REASON` 标记 `@Deprecated` |
| `application.yml` | 新增 `agent.subtask.*` + `feature.subagent.*` 配置项 |

### 7.3 保留不变的文件

| 文件 | 原因 |
|------|------|
| `agent/legacy/task/TaskExecutor.java` | 回退路径组件（P5 重整 `93c59a0` 已移除 @Deprecated，非死代码），`feature.subagent.enabled=false` 时使用 |
| `agent/legacy/task/TaskQueue.java` | 同上 |
| `agent/task/model/SubTask.java` | 仍作为 decompose() 输出（已入 task/model 子包） |
| `agent/task/model/SubTaskStatus.java` | 保留供日志/监控（五态两用：READY/RUNNING 为回退链专用） |
| `agent/task/model/TaskReport.java` | 仍用于多轮历史跟踪 |
| `agent/task/model/TaskSnapshot.java` | CONFIRM 续跑仍需要（恢复装配在 ConfirmResumeService） |
| 所有 tool/ 下文件 | 工具实现不变 |
| 所有 guard/ 下文件 | 安全守卫不变 |
| AiServiceImpl.java | Phase 1 不变 |
| AiResponseRouter.java | 路由逻辑不变 |
| SseUtils.java | SSE 工具方法不变 |

### 7.4 迁移步骤

```
Step 1: 创建 SubTaskProperties.java + FeatureProperties.java
Step 2: application.yml 中写入配置项
Step 3: AgentConfig 新增 subAgentChatClient Bean（无 defaultTools）
Step 4: 创建 subagent/ 包 + SubTaskPlan.java + SubTaskResult.java + ExecutionPromptBuilder.java
Step 5: 创建 SubTaskAgent.java（工具筛选 + JSON 快照解析 + 退避重试 + 结构化日志）
Step 6: TaskPlanner 改造（注入 SubTaskAgent + FeatureProperties；保留回退路径）
Step 7: TaskType.LLM_REASON 标记 @Deprecated
Step 8: TaskExecutor + TaskQueue 标注 @Deprecated
Step 9: 更新引用处文档（Agent任务队列方案.md、Agent模块架构设计.md）
Step 10: 测试场景覆盖：
        - 单工具 / 多工具 / 空计划
        - 工具调用成功 / 失败 / 部分失败
        - JSON 快照解析正常 / 缺失 / 格式错误
        - 子 Agent LLM 超时 / 重试耗尽
        - feature.subagent.enabled=false 回退
        - 多轮衔接历史正确
```
