# SSE 后端实现规范

## 1. 概述

本文档定义后端 SSE（Server-Sent Events）流式响应的实现规范，与前端 `md/SSE流式读取方案.md` 配合，实现 AI 聊天接口的逐字流式输出。

### 1.1 核心原则

- **对话固定 SSE 流式（2026-09-03 起）**：`/agent/string/send` 一律 SSE（`Accept` 内容协商已删除，JSON 同步对话废弃）
- **前端无感切换**：前端统一走 SSE 读取，不改 URL、不改调用方式
- **向后兼容**：历史数据（agent_conversation / agent_message）与查询接口保持不变

## 2. 响应模式（对话仅 SSE）

> **2026-09-03 起 `/agent/string/send` 固定 SSE 流式**：`Accept` 内容协商与 JSON 同步模式已废弃删除（`chatReturnStringResult` 移除，见《Agent模块链路迭代文档》Phase 27）。下方 JSON 描述保留为历史参考。

```
客户端请求                             后端行为
──────────────────────────────────────────────────────────
POST /agent/string/send?content=xxx   固定 SSE 流式响应（不再检查 Accept 头）
```

### 2.1 JSON 模式（历史，已废弃）

```json
HTTP/1.1 200 OK
Content-Type: application/json

{
  "success": true,
  "data": "AI 回复的完整文本",
  "errorMsg": null,
  "total": null
}
```

### 2.2 SSE 模式（对话唯一模式）

```http
HTTP/1.1 200 OK
Content-Type: text/event-stream;charset=UTF-8
Cache-Control: no-cache
Connection: keep-alive
```

```text
data: {逐段AI回复文本}
data: {逐段AI回复文本}
data: [DONE]
```

> **注意**：响应 **不包裹 Result 信封**，`data:` 行直接传输文本内容。

## 3. SSE 数据格式

### 3.1 标准事件格式

每条消息使用 SSE `data:` 字段，每行一个数据帧：

```
data: <文本内容>
```

### 3.2 文本分段规则

| 规则 | 说明 |
|------|------|
| 分段粒度 | 按语义自然断句分段（句号/问号/感叹号/换行处） |
| 最小粒度 | 至少 2-4 个汉字或一个完整单词，避免逐字输出 |
| 最大粒度 | 单帧不超过 1024 字节 |
| 缓冲区 | 不足一个完整语义片段时，暂不发送，等待更多内容 |

**示例**：

```text
data: 您好！我是小黑助手。
data: 您想查询什么商品信息呢？
data: [DONE]
```

### 3.3 终止标记

流结束时发送 `[DONE]` 标记：

```
data: [DONE]
```

- `[DONE]` **必须**单独占一个 `data:` 行
- 发送 `[DONE]` 后服务端**必须**关闭连接
- 前端检测到 `[DONE]` 后停止解析，resolve Promise

### 3.4 结构化数据尾帧（审核建议）

根据前端审核报告（§8.1），当 AI 回复需要携带结构化时，可在纯文本流之后、`[DONE]` 之前发送 JSON 尾帧：

```text
data: 正在为您查询今日优惠活动...
data: 已为您找到以下信息：
data: {"intent":"query","type":"promotion","data":{"count":3}}
data: [DONE]
```

前端在遇到以 `{` 开头的 `data:` 行时，按 JSON 解析并提取结构化数据。

## 4. 错误处理

### 4.1 HTTP 层错误（4xx/5xx）

| 场景 | HTTP 状态码 | 响应体 |
|------|------------|--------|
| 参数校验失败（content 为空） | 400 | 普通 JSON `Result.fail("参数错误")` |
| 未认证/Auth 失效 | 401 | 普通 JSON `Result.fail("未登录")` |
| 流中断/服务端异常 | 500 | 普通 JSON `Result.fail("服务异常")` |

> 即使请求携带 `Accept: text/event-stream`，异常时也返回普通 JSON，不返回 SSE。前端 `readSSE` 已适配：HTTP 非 2xx 直接抛异常，不走流式解析。

### 4.2 业务错误（SSE 模式，200）

AI 服务调用过程中的业务错误（如 AI 超时、内容过滤拦截），通过 SSE 流内传输：

```text
data: {"error": "AI 服务暂时不可用，请稍后重试", "code": 5001}
data: [DONE]
```

- 错误帧按照普通 `data:` 行发送
- 前端检测到 JSON 格式（以 `{` 开头）且含 `error` 字段，视为业务异常
- 错误帧后仍需发送 `[DONE]` 标记

## 5. 认证鉴权

### 5.1 Token 传递

SSE 请求使用 `fetch` 原生 API（非 axios），需在请求头中携带 token：

```
Authorization: Bearer <access_token>
```

服务端通过 `JwtConfig` / `LoginInterceptor` 统一校验。为确保拦截器放行 SSE 异步线程的上下文，需注意：

- SSE 模式在 `HandlerInterceptor.preHandle` 中完成认证
- 认证后的 `UserDTO` 通过 `RequestContextHolder` 获取
- 异步线程中如需用户信息，启动前从 `RequestContextHolder` 提取并传入

### 5.2 Cookie

SSE 请求携带 `credentials: 'include'`，后端可通过 httpOnly Cookie 获取 Refresh-Token。

## 6. 后端实现方案

### 6.1 技术选型

| 组件 | 方案 | 说明 |
|------|------|------|
| SSE 容器 | `SseEmitter` | Spring MVC 原生支持，基于 Servlet 异步请求 |
| AI 流式库 | `ChatClient.stream()` | Spring AI 的 `Flux<String>` 流式响应 |
| 桥接方式 | 异步线程 | 阻塞 AI 调用在独立线程执行，通过 `SseEmitter` 推送 |

### 6.2 时序

```
┌──────────┐      ┌──────────────┐      ┌───────────┐      ┌─────────┐
│  前端     │      │ ChatController│      │ AiService │      │ AI SDK  │
└────┬─────┘      └──────┬───────┘      └─────┬─────┘      └────┬────┘
     │ POST /string/send  │                    │                 │
     │ Accept: text/event-stream               │                 │
     │─────────────────────►                    │                 │
     │                     │ 创建 SseEmitter    │                 │
     │                     │ 开启异步线程        │                 │
     │                     │────────────────────►                │
     │                     │   chatStream()     │                 │
     │                     │                    │────────────────►│
     │    data: 您好！       │                    │  流式 chunk     │
     │◄────────────────────│◄───────────────────│◄───────────────│
     │    data: 您想...     │                    │                 │
     │◄────────────────────│◄───────────────────│◄───────────────│
     │    data: [DONE]     │                    │                 │
     │◄────────────────────│◄───────────────────│                │
     │                     │ emitter.complete() │                 │
```

### 6.3 SseEmitter 配置

```java
// 超时：30分钟（AI 长思考场景）
SseEmitter emitter = new SseEmitter(30 * 60 * 1000L);
// 或 0L 表示不超时（由客户端断开驱动）
SseEmitter emitter = new SseEmitter(0L);
```

### 6.4 流式推送核心逻辑

```java
// AiServiceImpl.java — 伪代码
public void chatStream(String content, SseEmitter emitter) {
    chatClient.prompt().user(content).stream().content()
        .subscribe(
            chunk -> {
                // 累积到语义完整的片段后发送
                SseEmitter.SseEventBuilder event = SseEmitter.event()
                    .data(chunk);
                emitter.send(event);
            },
            error -> {
                // 业务错误 → 发送错误 JSON 帧
                emitter.send(SseEmitter.event()
                    .data("{\"error\":\"" + error.getMessage() + "\",\"code\":5001}"));
                emitter.complete();
            },
            () -> {
                // 流结束，发送 [DONE]
                emitter.send(SseEmitter.event().data("[DONE]"));
                emitter.complete();
            }
        );
}
```

## 7. 现有代码改造清单

### 7.1 ChatController

修改 `/agent/string/send` 端点：
- 检查 `Accept` 请求头
- 如含 `text/event-stream` → 创建 `SseEmitter`，调用 `AiService.chatStream(content, emitter)`，返回 `emitter`
- 否则 → 保持现有 JSON 行为不变

### 7.2 AiService

新增方法：

```java
/**
 * 流式 AI 聊天
 * @param content 用户输入
 * @param emitter SSE 发射器，用于推送逐段结果
 */
void chatStream(String content, SseEmitter emitter);
```

### 7.3 AiServiceImpl

实现 `chatStream`：
- 使用 `ChatClient.prompt().user(content).stream()` 获取流
- 订阅 `Flux<String>`，`subscribe(chunk, error, complete)`
- 设置合理的语义分段缓冲（可选）

## 8. 边界情况

| 场景 | 处理方式 |
|------|---------|
| AI 服务超时 | 订阅 error 回调，发送错误 JSON 帧 + `[DONE]` |
| 客户端断开 | `SseEmitter` 抛 `AsyncRequestTimeoutException` 或 `IOException`，释放资源 |
| 并发请求 | 每个请求独立 `SseEmitter` 实例，无状态冲突 |
| 空响应（AI 无回复） | 直接发送 `[DONE]` |
| 内容过长 | Spring AI 和 SseEmitter 均支持大流，内存受单个 chunk 大小而非总输出控制 |
| 多次 `[DONE]` | 业务代码确保 `[DONE]` 仅发送一次（在 complete 回调中） |

## 9. 性能考虑

- **线程**：`SseEmitter` 使用 Servlet 异步线程，不占用 Tomcat 请求处理线程
- **缓冲区**：建议在服务端做 200ms 或语义句段合并，减少 SSE 事件频率
- **监控**：通过 `SseEmitter` 的 completion/timeout/error 回调做日志记录
