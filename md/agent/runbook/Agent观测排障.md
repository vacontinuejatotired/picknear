---
status: current
source_of_truth:
  - picknear/src/main/java/com/hmdp/agent/observability/api/AgentTracer.java
  - picknear/src/main/java/com/hmdp/agent/observability/core/AgentSpanImpl.java
  - picknear/src/main/java/com/hmdp/agent/stream/ObservedSseEmitter.java
  - picknear/src/main/resources/application.yaml
superseded_by:
---

# Agent 观测排障

本文处理 trace 断链、SSE 根 span 不结束、Langfuse 无数据和 OTLP 接入问题。设计见
[Agent 观测设计](../Agent观测设计.md)。

## 1. 先确认现象

| 现象 | 优先检查 |
|---|---|
| 每个 span 不同 traceId | start/openScope 顺序、跨线程 resume |
| Langfuse 中 span 全部平铺 | session 根 span 是否结束 |
| 新会话挂到旧会话 | 根 scope 是否跨线程泄漏 |
| LLM input/output 为空 | 补发 key 和 include-content |
| 页面完全没有数据 | OTLP endpoint、凭据、后端 type |

## 2. TraceId 断链

### 2.1 根因一：创建顺序错误

正确顺序：

```text
observation.start()
observation.openScope()
```

错误顺序会提前写入空 `TracingContext`，使 OTel 父级丢失。

### 2.2 根因二：Reactor context 污染

流式调用绕过 `ChatClient`，直接使用 `ChatModel.stream()`，并把当前 Observation
写入 Reactor context。

不要回退到 `ChatClient.stream()` 而不重新验证 trace 树。

### 2.3 根因三：异步线程未 resume

跨线程代码必须：

```text
try (Scope scope = tracer.resume(rootSpan)) {
    ...
}
```

检查线程池是否装配：

- `aiTaskExecutor`
- `subtaskExecutor`
- 并行工具线程
- 压缩线程池

## 3. SSE 根 span 不结束

### 3.1 检查结束路径

`ObservedSseEmitter` 必须覆盖：

- complete
- completeWithError
- onTimeout
- onError
- 兜底 TTL

任一覆盖缺失都可能造成 session 永久不导出。

### 3.2 检查根 scope

根 scope 只能在创建它的请求线程关闭。若异步线程关闭其它线程 scope，会造成：

- 请求线程残留 scope；
- Tomcat 线程复用时跨会话污染；
- 关闭线程自己的 current scope 被错误覆盖。

### 3.3 检查 guard TTL

约束：

```text
guardDelayMs > timeoutMs
```

不满足时构造阶段应直接失败。

## 4. 页面完全没有数据

检查：

1. `hmdp.ai-observability.trace-enabled=true`；
2. `hmdp.ai-observability.backend.type` 合法；
3. `management.otlp.tracing.endpoint` 是否带 `/v1/traces`；
4. Authorization 是否正确；
5. Langfuse 摄入 header 是否存在；
6. trace filter 是否允许当前 span 前缀。

Langfuse 详细配置见 [Langfuse 云接入说明](../../ops/Langfuse云接入说明.md)。

## 5. LLM input/output 为空

检查：

1. `ChatModelObservationConventionConfig` 是否补发
   `langfuse.observation.input/output`；
2. `hmdp.ai-observability.chat-observation.include-content` 是否允许；
3. 当前后端能力是否要求补发；
4. 新 trace 是否在修复部署后生成。

不要再使用旧 `gen_ai.request/response.content` 作为主字段。

## 6. 自定义属性查不到

Langfuse OTLP 路径下自定义 attributes 不可用于 evaluator 映射。

处理方式：

- UI 查阅：检查 span attributes；
- 评测取数：必须走 observation 主字段；
- 语义区分：依赖 generation 名编码。

不要尝试 metadata jsonSelector。

## 7. 后端切换

后端 type：

```text
langfuse
jaeger
signoz
collector
console
noop
```

切换步骤：

1. 修改 `hmdp.ai-observability.backend.type`；
2. 修改 `management.otlp.tracing.*`；
3. 产生新请求；
4. 检查新 trace 树；
5. 验证 content、attributes 和命名是否符合后端能力。

未知 type 会 Fail-Open 到 Noop。

## 8. 验证命令

本地文档检查：

```bash
python scripts/docs/check_docs.py
```

查看最近观测：

```bash
lf traces --limit 5
lf obs --limit 10
```

CLI 细节见 [Langfuse CLI 使用指南](../../ops/Langfuse%20CLI%20使用指南.md)。

## 9. 修改完成条件

1. 一次真实请求的 Agent span 在同一 trace 树；
2. `agent.session` 正常结束并带 finish reason；
3. 跨线程工具和 guard span 能正确挂树；
4. LLM input/output 在 Langfuse 主字段可见；
5. 切换后端后备注能力差异。
