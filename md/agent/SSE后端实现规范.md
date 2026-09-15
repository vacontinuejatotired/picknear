---
status: current
source_of_truth:
  - picknear/src/main/java/com/hmdp/agent/controller/ChatController.java
  - picknear/src/main/java/com/hmdp/agent/stream/SseSessionFactory.java
  - picknear/src/main/java/com/hmdp/agent/stream/SseUtils.java
  - picknear/src/main/java/com/hmdp/agent/stream/SseEventConstants.java
  - picknear/src/main/java/com/hmdp/agent/stream/ObservedSseEmitter.java
superseded_by:
---

# SSE 后端实现规范

本文定义 Agent 对话的 SSE 传输契约。前端读取实现位于前端仓库，后端不维护前端副本。

## 1. 端点

| 端点 | 方法 | 响应 |
|---|---|---|
| `/agent/string/send` | POST | SSE |
| `/agent/confirm` | POST | `Accept: text/event-stream` 时 SSE，否则 JSON |
| `/agent/reject` | POST | JSON |

`/agent/string/send` 不进行 JSON/SSE 内容协商，固定 SSE。JSON 同步对话已经删除。

## 2. HTTP 契约

```http
HTTP/1.1 200 OK
Content-Type: text/event-stream;charset=UTF-8
Cache-Control: no-cache
Connection: keep-alive
```

请求认证使用：

```http
Authorization: Bearer <access_token>
```

请求参数：

| 参数 | 必填 | 说明 |
|---|---|---|
| `content` | 是 | 用户输入 |
| `conversationId` | 否 | 首次为空，后端生成 |

## 3. 帧格式

服务端使用 `SseEmitter.event().data(...)` 发送 `data:` 帧。

普通回答文本直接发送：

```text
data: 你好
data: ，我来查询。
```

结构化事件发送 JSON：

```text
data: {"type":"meta","conversationId":"..."}
data: {"type":"plan","round":1,"tasks":[...]}
```

当前后端不以 `[DONE]` 作为终止协议。**流结束以 HTTP/SSE 连接 EOF 为准**，服务端通过
`emitter.complete()` 结束响应。

`[DONE]` 只存在于旧前端兼容逻辑中，不是当前后端发送的事件。

## 4. 事件类型

### 4.1 meta

```json
{
  "type": "meta",
  "conversationId": "..."
}
```

首个结构化事件，前端保存会话 ID。

### 4.2 plan

```json
{
  "type": "plan",
  "round": 1,
  "tasks": [
    {
      "id": "t1",
      "description": "查询天气",
      "toolName": "queryWeather",
      "type": "TOOL_CALL",
      "status": "PENDING"
    }
  ]
}
```

每轮规划后发送全量任务清单。

### 4.3 progress

普通阶段：

```json
{
  "type": "progress",
  "stage": "planning|executing|merging",
  "text": "..."
}
```

工具步骤：

```json
{
  "type": "progress",
  "stage": "step",
  "taskId": "t1",
  "toolName": "queryWeather",
  "description": "查询天气",
  "status": "RUNNING"
}
```

### 4.4 confirm

```json
{
  "type": "confirm",
  "confirmId": "cfm_xxx",
  "tool": "publishTestBlog",
  "reason": "需要确认",
  "arguments": "..."
}
```

前端必须提供确认和拒绝操作。确认续流继续使用同一协议。

### 4.5 error

```json
{
  "type": "error",
  "error": "AI 服务暂时不可用",
  "code": 5001
}
```

错误事件后由服务端结束流。

## 5. 工具状态

| status | 含义 |
|---|---|
| `RUNNING` | 开始执行 |
| `COMPLETED` | 执行成功 |
| `FAILED` | 执行失败 |
| `SKIPPED` | 计划存在但未调用 |

`taskId` 存在时按 taskId 定位任务；只有 `toolName` 时按工具名更新。

## 6. 生命周期

`SseSessionFactory` 统一创建：

- `agent.session` 根 span；
- `ObservedSseEmitter`；
- `meta` 事件。

根 span 生命周期与 emitter 绑定。所有结束路径最终都结束根 span，见
[Agent 观测设计](Agent观测设计.md)。

## 7. 错误和断开

| 场景 | 行为 |
|---|---|
| 初始化异常 | 发送 error 事件后 complete |
| 模型重试耗尽 | 发送 error 事件后 complete |
| 客户端断开 | `safeSend` 忽略 IOException / IllegalStateException |
| emitter 已结束 | 不再发送，静默忽略 |
| CONFIRM 暂停 | 发送 confirm 并结束当前 SSE，等待续流 |

SSE 响应一旦开始，连接内错误不再回退为普通 JSON。

## 8. 前端对接要求

1. 使用 `fetch` 读取 `ReadableStream`；
2. 按 SSE 行解析 `data:`；
3. `meta` 先建立会话；
4. JSON 事件按 `type` 分发；
5. 普通文本按 token 追加；
6. EOF 表示结束；
7. 同时兼容历史 `[DONE]`，但后端新协议不发送。

## 9. 修改约束

1. 事件类型由 `SseEventConstants` 和 `SseUtils` 单一维护。
2. 不手工拼接 JSON，统一使用 `SseUtils`。
3. 修改事件字段必须同时更新前端读取实现。
4. 协议变更必须在本文更新。
5. 不允许重新引入 JSON 同步对话入口。
