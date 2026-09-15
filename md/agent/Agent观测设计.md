---
status: current
source_of_truth:
  - picknear/src/main/java/com/hmdp/agent/observability/api/AgentTracer.java
  - picknear/src/main/java/com/hmdp/agent/observability/core/AgentSpanImpl.java
  - picknear/src/main/java/com/hmdp/agent/observability/backend/TraceBackend.java
  - picknear/src/main/java/com/hmdp/agent/observability/backend/TraceBackendAssembler.java
  - picknear/src/main/java/com/hmdp/agent/observability/backend/TraceBackendCapabilities.java
  - picknear/src/main/java/com/hmdp/agent/stream/ObservedSseEmitter.java
  - picknear/src/main/java/com/hmdp/agent/config/ChatModelObservationConventionConfig.java
  - picknear/src/main/resources/application.yaml
superseded_by:
---

# Agent 观测设计

本文描述 Agent 业务 span、OTLP 链路、观测后端和 SSE 根 span 生命周期。操作与排障见
[Agent 观测排障](runbook/Agent观测排障.md)。

## 1. 目标

一次 Agent 请求跨多个线程池，调用多轮模型和工具。观测设计解决：

1. 全链路 span 使用同一 traceId；
2. 异步线程能够恢复父级上下文；
3. SSE 所有结束路径都能关闭会话根 span；
4. 业务语义不绑定 Langfuse；
5. Langfuse 评测能够读取 LLM 主字段。

## 2. 分层

| 层 | 实现 |
|---|---|
| 业务埋点 | `AgentTracer` / `AgentSpan` |
| LLM 调用观测 | Spring AI + Micrometer Observation |
| 跨进程协议 | OpenTelemetry OTLP |
| 展示与评测 | Langfuse，或其它 OTLP 后端 |

业务代码只依赖 `AgentTracer`，不直接创建 Micrometer 或 OTel 对象。

## 3. Span 树

典型请求：

```text
agent.session
  -> agent.prompt_hook
  -> agent.phase1
  -> agent.decision
  -> agent.round.1
     -> agent.plan
     -> agent.subagent
        -> agent.guard.-a-l-l-o-w.queryWeather
        -> tool_call.queryWeather
```

主要业务 span：

| spec | 语义 |
|---|---|
| `session` | 一个 SSE 会话根 |
| `prompt_hook` | 输入 Hook 链 |
| `phase1` | Phase 1 流式调用及重试 |
| `decision` | AfterAiHook 聚合决策 |
| `round` | 一轮规划执行 |
| `plan` | 规划模型调用与校验 |
| `subagent` | 一轮子 Agent 工具执行 |
| `guard` | 单次工具守卫决策 |
| `tool_call` | 单次工具调用 |

## 4. AgentTracer

`AgentTracer` 是埋点唯一入口：

- `startSession` 创建会话根 span；
- `start` 创建普通业务 span；
- `startGuard` 创建带 Guard 语义的 span；
- `resume` 在异步线程重新挂载根 span。

埋点 Fail-Open：观测自身异常降级为 `NoopAgentSpan`，不阻断主链。

## 5. 生命周期与断链修复

### 5.1 创建顺序

Micrometer Observation 必须：

```text
observation.start()
  -> observation.openScope()
```

不能先 `openScope` 再 `start`。否则空的 `TracingContext` 会提前占位，导致 OTel
父 span 丢失并产生新的 traceId。

### 5.2 跨线程传播

主链会进入：

- `aiTaskExecutor`
- `subtaskExecutor`
- 并行工具线程
- 压缩线程池

异步线程进入时必须恢复根 span：

```text
try (Scope scope = agentTracer.resume(rootSpan)) {
    ...
}
```

线程池任务通过 `AgentContextPropagator` 传播 `AgentContext`。

### 5.3 根 scope 清理

根 scope 由创建它的请求线程负责关闭。关闭动作必须返回属主线程，避免：

- Tomcat 线程复用后继承上一会话根 scope；
- 异步线程关闭其它线程 scope；
- 新会话挂到旧会话下。

### 5.4 ObservedSseEmitter

`ObservedSseEmitter` 将 SSE 生命周期与根 span 绑定。

所有结束路径都收敛到 `finish(reason)`：

| 路径 | reason |
|---|---|
| 正常 complete | `COMPLETE` |
| completeWithError | `ERROR` |
| 容器 onError | `ERROR` |
| 容器超时 | `TIMEOUT` |
| 兜底 TTL | `TIMEOUT` |

使用 CAS 保证 only-once。根 span 为 null 时退化为普通 emitter。

## 6. 观测后端

### 6.1 策略接口

`TraceBackend` 只描述能力，不保存 endpoint 和凭据。

支持的 type：

```text
langfuse
jaeger
signoz
collector
console
noop
```

`TraceBackendAssembler` 按 `hmdp.ai-observability.backend.type` 选择实现。

### 6.2 能力驱动

`TraceBackendCapabilities` 决定：

| 能力 | 行为 |
|---|---|
| `supportsSpanAttributes` | 是否把语义编码进 span 名 |
| `contentSupplementRequired` | 是否补发 LLM input/output |
| `quotaAware` | 是否使用严格白名单 |
| `defaultTracePrefixes` | 默认放行 `agent.`、`spring.ai.`、`gen_ai.` |

Langfuse 不展示自定义 attributes，因此把调用方语义编码进 generation 名。

### 6.3 接入参数

OTLP endpoint 和凭据唯一事实源为：

```text
management.otlp.tracing.*
```

后端类型只控制行为适配，不复制 endpoint 配置。

## 7. LLM 主字段补发

`ChatModelObservationConventionConfig` 补发：

```text
langfuse.observation.input
langfuse.observation.output
```

这是 Langfuse OTLP 转译主字段的最高优先级 key，评测依赖它。

旧的 `gen_ai.request/response.content` 不识别，不能重新使用。

`ChatContentSerializer` 负责：

- 消息角色序列化；
- tool response；
- tool calls；
- 脱敏和截断。

## 8. 配置

```yaml
hmdp:
  ai-observability:
    backend:
      type: langfuse
    trace-enabled: true
    trace-filter:
      include-prefixes:
        - agent.
        - spring.ai.
        - gen_ai.
    chat-observation:
      include-content: true
    span-naming:
      semantic-encoding: auto
```

完整值以 `application.yaml` 为准。

## 9. 失败降级

| 故障 | 行为 |
|---|---|
| 未知后端 | 降级 Noop |
| 埋点异常 | 返回 Noop span，主链继续 |
| OTLP endpoint 缺失 | 主链继续，导出失败告警 |
| SSE 根 span 缺失 | 退化普通 emitter |
| span 已结束 | `safeSend` 忽略后置发送 |

## 10. 扩展约束

1. 新后端实现 `TraceBackend` 并注册为 Bean。
2. 不在业务代码中判断 Langfuse。
3. 不把 endpoint、token 写入后端策略实现。
4. 新增 span 必须通过 `AgentTracer`。
5. 跨线程代码必须恢复根 span scope。
6. 观测失败不能阻断业务主链。

## 11. 关联文档

- SSE 事件协议见 [SSE 后端实现规范](SSE后端实现规范.md)。
- Langfuse 操作指南见 `md/ops/`。
- 断链排查与验证见 [Agent 观测排障](runbook/Agent观测排障.md)。
