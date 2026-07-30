# Agent 模块发展路线图

> **版本**: v1.0  
> **创建日期**: 2026-07-22  
> **基于**: 架构设计 v1.3 完整代码审查  
> **相关文档**: [Agent模块架构设计](./Agent模块架构设计.md), [SSE后端实现规范](./SSE后端实现规范.md)

---

## 目录

1. [现状评估](#1-现状评估)
2. [发展方法论](#2-发展方法论)
3. [数据模型现状与重构](#3-数据模型现状与重构)
    - [3.1 当前数据模型全景](#31-当前数据模型全景)
    - [3.2 缺失的数据结构](#32-缺失的数据结构)
    - [3.3 核心数据模型设计](#33-核心数据模型设计)
    - [3.4 各阶段的数据结构演进路线](#34-各阶段的数据结构演进路线)
4. [Phase 0 — 清理与加固（1-2 天）](#4-phase-0--清理与加固1-2-天)
5. [Phase 1 — 基础设施补全（3-5 天）](#5-phase-1--基础设施补全3-5-天)
6. [Phase 2 — 从 Function Calling 到真正 Agent（1-2 周）](#6-phase-2--从-function-calling-到真正-agent1-2-周)
7. [Phase 3 — 业务工具生态（2-3 周）](#7-phase-3--业务工具生态2-3-周)
8. [Phase 4 — 生产级能力（1-2 月）](#8-phase-4--生产级能力1-2-月)
9. [Phase 5 — 智能进化（远期）](#9-phase-5--智能进化远期)
10. [附录：每个阶段的可交付物清单](#10-附录每个阶段的可交付物清单)

---

## 1. 现状评估

### 1.1 架构成熟度

```
                          Phase 0 前      Phase 0 后
Controller                ██████████ 100%  ██████████ 100%  ✅ 已清理
Service                   ██████████ 100%  ██████████ 100%  ✅ 线程池就绪
Guard                     ██████████ 100%  ██████████ 100%  
Permission                ██████████ 100%  ██████████ 100%  
                          ──────────────  ──────────────
Tool 生态                 ██░░░░░░░░ 20%   ██░░░░░░░░ 20%   → Phase 3
Agent 核心                ██░░░░░░░░ 30%   ██░░░░░░░░ 30%   → Phase 2
流式体验                  ██░░░░░░░░ 20%   ██░░░░░░░░ 20%   → Phase 2
测试                      ░░░░░░░░░░  0%   ░░░░░░░░░░  0%   → Phase 1
安全审批                  ██░░░░░░░░ 30%   ██░░░░░░░░ 30%   → Phase 1
监控                      ░░░░░░░░░░  0%   ░░░░░░░░░░  0%   → Phase 4
```

### 1.2 已具备的优势（不要破坏这些）

| 优势 | 说明 | 保护策略 |
|------|------|---------|
| **分层架构** | Controller → Service → Tool → Guard → Permission，关注点分离清晰 | 新增功能遵循现有分层，不跨层绕路 |
| **自动扫描注册** | `@TargetTool` 注解 + `ToolBeanCollector` 自动收集 | 新增工具永远不修改 AgentConfig |
| **两层纵深防御** | Guard（无状态前置）+ Permission（有状态 AOP） | 不改动现有安全链路，只往上加策略 |
| **双模响应** | 同一端点支持 JSON 和 SSE | 保持 Accept 头协商机制，不做第二个端点 |
| **文档完备** | 架构文档有 ADR（设计决策记录） | 每个阶段修改同步更新文档 |

### 1.3 必须立即解决的问题

| 问题 | 等级 | 状态 | 说明 |
|------|------|------|------|
| `PromptGuard.java` 死代码 | 🔴 P0 | ✅ Phase 0 已删除 | 源文件已移除 |
| `postMethodName` 空 API | 🟡 P2 | ✅ Phase 0 已删除 | 空方法已移除 |
| `CompletableFuture` 无线程池 | 🟠 P1 | ✅ Phase 0 已修复 | `AgentConfig` 已配置 `aiTaskExecutor` 并注入 |
| SSE JSON 注入漏洞 | 🟠 P1 | ✅ Phase 0 已修复 | `escapeJson()` 已用于 `hookResult.getReason()` |
| SSE conversationId 推送失败 | 🟠 P1 | ✅ Phase 0 已修复 | `completeWithError` + `return null` |
| CONFIRM 空壳 | 🔴 P0 | 📅 Phase 1 | 需要真实的审批确认机制 |
| 流式是伪流式 | 🟠 P1 | 📅 Phase 2 | 需要 `stream()` 真流式 + SSE 事件类型 |

---

## 2. 发展方法论

### 2.1 核心原则

1. **增量演进，不重写** — 现有架构骨架是对的，每个阶段在现有结构上做加法
2. **每阶段可独立发布** — 每个 Phase 结束时都是可工作的状态，不依赖后续阶段
3. **测试先行** — Phase 0/1 开始补测试，后续每个新功能必有测试
4. **文档同步** — 任何行为变更同步更新 `Agent模块架构设计.md`

### 2.2 优先级逻辑

```
安全 > 稳定 > 体验 > 功能 > 智能
        ↓
Phase 0 → Phase 1 → Phase 2 → Phase 3 → Phase 4 → Phase 5
(清理)   (补漏)    (质变)    (丰富)    (生产)    (进化)
```

---

## 3. 数据模型现状与重构

> 这是第一版路线图缺失的内容——感谢审查反馈指出。数据模型是 Agent 模块的"骨架"，骨架不正，上面的功能都是歪的。

### 3.1 当前数据模型全景

先看看当前实际在用的数据结构有哪些、存在哪里：

#### 持久化存储（数据库 / Redis）

| 数据结构 | 存储位置 | 用途 | 状态 |
|---------|---------|------|------|
| `chat_memory` 表（Spring AI 自动建表） | MariaDB | 多轮对话历史 (MessageWindowChatMemory) | ✅ 自动生成，但项目无控制权 |
| `guard:rate:{conversationId}` (String) | Redis | 工具调用频率计数器 (RateLimitPolicy) | ✅ 正常 |
| `memory:user:{userId}` (Hash) | Redis | 长期记忆（Phase 5 规划） | ❌ 不存在 |

#### 内存对象（Java POJO）

| 类 | 包 | 用途 | 状态 |
|----|----|------|------|
| `ToolInvocationContext` | `promptguard` | 守卫评估上下文（工具名、参数、会话ID、用户ID） | ✅ 正常 |
| `GuardResult` | `promptguard` | 守卫决策结果（ALLOW/BLOCK/CONFIRM + 原因） | ✅ 正常 |
| `Vote` | `promptguard` | 策略投票枚举 (ALLOW/BLOCK/CONFIRM/ABSTAIN) | ✅ 正常 |
| `ChatContext` | `prompthook` | Hook 评估上下文（userId, conversationId, history） | ✅ 正常 |
| `HookResult` | `prompthook` | Hook 决策结果（PASS/BLOCK/REPLACE + 替换文本） | ✅ 正常 |
| `PromptGuardProperties` | `config` | YAML 配置绑定（黑名单/确认名单/正则/限流） | ✅ 正常 |
| `AgentResult` | 不存在 | Agent 执行结果 | ❌ Phase 2 需新建 |
| 审批确认记录 | 不存在 | 待审批的工具调用 | ❌ Phase 1 需新建 |

#### SSE 有线格式（网络传输协议）

| 事件类型 | 格式 | 用途 | 状态 |
|---------|------|------|------|
| 普通文本 | `data: {text}` | 推送 AI 回复 | ✅ 当前在用 |
| 错误帧 | `data: {"error":"...","code":5001}` | 推送业务错误 | ✅ 当前在用 |
| 终止标记 | `data: [DONE]` | SSE 流结束 | ✅ 当前在用 |
| conversationId | `data: conversationId:xxx` | 通知前端会话 ID | ✅ 当前在用 |
| 结构化事件 | `data: {"type":"...","content":"..."}` | 区分 thought/tool_call/text/done | ❌ Phase 2 需定义 |

---

### 3.2 缺失的数据结构

逐个分析当前缺失的数据模型，以及它们会导致什么问题：

#### 🔴 P0 — 缺少会话元数据表

**现状**: `chat_memory` 表是 Spring AI 自动创建的，只存消息内容，没有会话级别的元数据。

**问题**:
1. 无法查询"某个用户有哪些历史会话"（没有 `user_id` 字段）
2. 无法标记会话标题/状态（活跃/已归档）
3. 无法统计"每个用户创建了多少次对话"
4. 无法设置会话级 TTL（过期清理）

#### 🔴 P0 — 缺少 SSE 事件类型协议

**现状**: SSE 的 `data:` 行没有统一格式，文本直接发文本，错误直接拼 JSON。Phase 2 引入 ReAct 后，需要区分 text/thought/tool_call/error/done 等事件类型。

**问题**:
1. 前端解析靠"猜"——以 `{` 开头就当 JSON，否则当文本
2. 无法扩展新事件类型（比如"让前端显示进度条"就没法做）
3. 无法携带元数据（事件 ID、时间戳）

#### 🟠 P1 — 缺少审批记录表

**现状**: CONFIRM 决策返回字符串就给 LLM 自己处理了，没有持久化、没有状态管理。

**问题**:
1. 重复确认：用户说"确认"可能触发多次
2. 无超时：待确认的操作永远挂起
3. 无审计：谁在什么时候确认了什么操作，完全不可追溯

#### 🟠 P1 — 缺少 Agent 执行轨迹表

**现状**: Agent 的思考过程、工具调用、观察结果只在日志里一闪而过。

**问题**:
1. Agent 犯错时无法回溯（"上次它为什么调了那个工具？"）
2. 无法分析工具调用模式（哪个工具被调最多？哪个经常失败？）
3. 无法做 A/B 测试（Prompt 修改前后推理路径变没变？）

#### 🟠 P1 — 缺少提示词模板表

**现状**: 系统提示词硬编码在 `AgentConfig.java` 里，工具描述在 `@Tool(description=...)` 注解里。

**问题**:
1. 改提示词要改代码、重新编译、重新部署
2. 无法做多版本管理（线上 vs 测试用不同提示词）
3. 无法动态注入上下文（用户姓名、当前城市、时间）

#### 🟡 P2 — 缺少用户偏好数据结构

**现状**: 零。用户的任何偏好信息都不被记录。

**问题**:
1. 每次对话都是"陌生人"，哪怕用户上周说过"我爱吃辣"
2. 无法做个性化推荐
3. Agent 回复千篇一律

#### 🟡 P2 — 缺少工具注册元数据

**现状**: 工具只有代码里的 `@Tool(description=...)`，没有中心化的注册信息。

**问题**:
1. 无法查询"当前系统有哪些可用工具"
2. 无法动态启用/禁用工具（现有 `active` 注解只能重启生效）
3. LLM 看到的工具描述只能在编译时确定

---

### 3.3 核心数据模型设计

以下是为解决上述问题设计的数据模型。**每张表/结构都标注了由哪个 Phase 实现，不要求一步到位。**

#### 3.3.1 会话表 — `agent_conversation`

**对应缺失**: P0 — 会话元数据  
**实现阶段**: Phase 1  
**存储**: MariaDB

```sql
CREATE TABLE agent_conversation (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    conversation_id VARCHAR(64)  NOT NULL COMMENT '全局唯一会话 ID（UUID）',
    user_id         BIGINT       NOT NULL COMMENT '所属用户',
    title           VARCHAR(200) DEFAULT NULL COMMENT '会话标题（由首轮消息摘要生成）',
    status          TINYINT      NOT NULL DEFAULT 0 COMMENT '0-活跃 1-已归档 2-已删除',
    model_name      VARCHAR(64)  DEFAULT NULL COMMENT '使用的模型名称',
    message_count   INT          NOT NULL DEFAULT 0 COMMENT '消息总数',
    token_count     INT          NOT NULL DEFAULT 0 COMMENT '累计 Token 消耗',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    expired_at      DATETIME     DEFAULT NULL COMMENT '自动过期时间',

    INDEX idx_user_id (user_id),
    INDEX idx_conversation_id (conversation_id),
    INDEX idx_status (status),
    INDEX idx_expired_at (expired_at)
) COMMENT 'AI 对话会话元数据';
```

**设计要点**:
- `conversation_id` 是业务标识（UUID，前端 API 传的就是它），`id` 是内部自增主键
- 用户删除会话走软删除（`status=2`），保留审计数据
- `expired_at` 由定时任务扫描清理，默认 TTL 7 天

#### 3.3.2 消息表 — `agent_message`

**对应缺失**: P0 — 会话元数据 (补充 Spring AI 自动表的不足)  
**实现阶段**: Phase 1  
**存储**: MariaDB

```sql
CREATE TABLE agent_message (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    conversation_id VARCHAR(64)  NOT NULL COMMENT '所属会话 ID',
    role            VARCHAR(16)  NOT NULL COMMENT 'user / assistant / system / tool',
    content         TEXT         NOT NULL COMMENT '消息内容',
    content_type    VARCHAR(32)  NOT NULL DEFAULT 'text' COMMENT 'text / thought / tool_call / tool_result',
    tool_name       VARCHAR(64)  DEFAULT NULL COMMENT '如果是工具调用/结果，记录工具名',
    tool_arguments  JSON         DEFAULT NULL COMMENT '如果是工具调用，记录参数 JSON',
    tool_result     JSON         DEFAULT NULL COMMENT '如果是工具结果，记录结果 JSON',
    token_count     INT          DEFAULT NULL COMMENT '本条消息的 Token 数',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,

    INDEX idx_conversation_id (conversation_id),
    INDEX idx_created_at (created_at)
) COMMENT 'AI 对话消息明细';
```

**与 Spring AI 的 `chat_memory` 表的关系**:

| 表 | 用途 | 维护方 | 保留 |
|----|------|--------|------|
| `chat_memory` | Spring AI 内部用于 `MessageChatMemoryAdvisor` 构造上下文 | Spring AI 自动 | 保留，不改 |
| `agent_message` | 业务查询、审计、分析 | 业务代码 | **新增** |

两条写入路径互不干扰：Spring AI 写 `chat_memory`，业务代码在每次 LLM 调用后写 `agent_message`。

#### 3.3.3 SSE 事件协议

**对应缺失**: P0 — SSE 事件类型协议  
**实现阶段**: Phase 2  
**存储**: 有线格式（不落盘）

```typescript
// SSE 事件格式规范
// 每行 data: 后面是一个 JSON 对象

// 1. 文本块（真流式下逐段推送）
data: {"type":"text","content":"为您推荐","seq":1}

// 2. Agent 推理过程
data: {"type":"thought","content":"用户想吃辣，我需要查川菜馆","seq":2}

// 3. 工具调用通知
data: {"type":"tool_call","tool":"queryShopByType","args":{"keyword":"川菜"},"seq":3}

// 4. 工具调用结果
data: {"type":"tool_result","tool":"queryShopByType","success":true,"summary":"找到3家店铺","seq":4}

// 5. 错误（业务异常，非 HTTP 错误）
data: {"type":"error","code":5001,"message":"AI 服务超时","seq":5}

// 6. 需要用户确认
data: {"type":"confirm","confirm_id":"cfm_xxx","tool":"publishTestBlog","reason":"需要确认后才能发布","seq":6}

// 7. 流结束
data: {"type":"done"}
```

**设计要点**:
- `seq` 序号保证前端按序渲染，防止异步乱序
- `tool_call` 中的 `args` 是工具参数，前端可用于展示"正在查询xxx"
- `tool_result` 不传完整结果（可能很大），只传 `summary` 摘要，完整结果 LLM 有
- `confirm` 事件让前端弹确认对话框，用户确认后前端调 `/agent/confirm` 接口

**Java 对应枚举**:

```java
public enum SseEventType {
    TEXT,       // 文本块
    THOUGHT,    // Agent 推理过程
    TOOL_CALL,  // 工具调用
    TOOL_RESULT,// 工具调用结果
    CONFIRM,    // 需要确认
    ERROR,      // 业务错误
    DONE        // 流结束
}

@Data
@AllArgsConstructor
public class SseEvent {
    private SseEventType type;
    private String content;
    private Integer seq;
    private String tool;
    private Object args;
    private Boolean success;
    private String summary;
    private String confirmId;
    private Integer code;
    private String message;
}
```

#### 3.3.4 审批记录表 — `agent_approval`

**对应缺失**: P1 — 审批记录  
**实现阶段**: Phase 1  
**存储**: MariaDB

```sql
CREATE TABLE agent_approval (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    confirm_id      VARCHAR(64)  NOT NULL COMMENT '全局唯一确认 ID',
    conversation_id VARCHAR(64)  NOT NULL COMMENT '所属会话 ID',
    user_id         BIGINT       NOT NULL COMMENT '需要确认的用户',
    tool_name       VARCHAR(64)  NOT NULL COMMENT '待确认的工具名',
    tool_arguments  JSON         NOT NULL COMMENT '工具参数',
    status          VARCHAR(16)  NOT NULL DEFAULT 'pending' COMMENT 'pending / approved / rejected / expired',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    decided_at      DATETIME     DEFAULT NULL COMMENT '确认/拒绝时间',
    expired_at      DATETIME     NOT NULL COMMENT '过期时间（默认创建后5分钟）',

    UNIQUE INDEX idx_confirm_id (confirm_id),
    INDEX idx_conversation_id (conversation_id),
    INDEX idx_status (status),
    INDEX idx_expired_at (expired_at)
) COMMENT '工具调用审批记录';
```

**流程**:

```
GuardedToolCallback 返回 CONFIRM
  ↓
创建 agent_approval 记录（status=pending, expired_at=now+5min）
  ↓
SSE 推送 confirm 事件到前端
  ↓
┌─ 用户 5 分钟内点击"确认" → POST /agent/confirm { confirmId } → status=approved
│  └─ AiServiceImpl 检查到已确认，重新执行工具调用
│
┌─ 用户 5 分钟内点击"拒绝" → POST /agent/reject { confirmId } → status=rejected
│  └─ SSE 推送错误帧 "操作已取消"
│
┌─ 5 分钟超时 → status=expired
   └─ SSE 推送错误帧 "确认已过期，请重新发起"
```

#### 3.3.5 Agent 执行轨迹表 — `agent_trace`

**对应缺失**: P1 — Agent 执行轨迹  
**实现阶段**: Phase 2  
**存储**: MariaDB（或 Elasticsearch 用于分析）

```sql
CREATE TABLE agent_trace (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    trace_id        VARCHAR(64)  NOT NULL COMMENT '全局唯一轨迹 ID',
    conversation_id VARCHAR(64)  NOT NULL COMMENT '所属会话',
    user_id         BIGINT       NOT NULL COMMENT '用户',
    iteration       INT          NOT NULL COMMENT '第几轮推理（从1开始）',
    thought         TEXT         DEFAULT NULL COMMENT 'LLM 的推理文本',
    tool_name       VARCHAR(64)  DEFAULT NULL COMMENT '调用的工具名',
    tool_arguments  JSON         DEFAULT NULL COMMENT '工具参数',
    tool_result     JSON         DEFAULT NULL COMMENT '工具返回结果',
    observation     TEXT         DEFAULT NULL COMMENT '观察（工具返回后 LLM 的下一步反应）',
    latency_ms      INT          DEFAULT NULL COMMENT '本轮耗时',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,

    INDEX idx_trace_id (trace_id),
    INDEX idx_conversation_id (conversation_id),
    INDEX idx_user_id (user_id)
) COMMENT 'Agent 多步推理轨迹';

-- 方便分析的单条轨迹视图（非必要，看场景）
-- SELECT * FROM agent_trace WHERE trace_id = ? ORDER BY iteration;
```

**数据示例**:

```json
{
  "trace_id": "trc_20260722_abc123",
  "conversation_id": "conv_001",
  "user_id": 42,
  "iteration": 1,
  "thought": "用户想找川菜馆，我需要先查川菜的类型ID",
  "tool_name": "queryShopType",
  "tool_arguments": {"keyword": "川菜"},
  "tool_result": {"typeId": 1, "typeName": "川菜"},
  "observation": "川菜类型 ID 是 1，接下来按距离查询店铺",
  "latency_ms": 2340
}
```

#### 3.3.6 提示词模板表 — `agent_prompt_template`

**对应缺失**: P1 — 提示词模板  
**实现阶段**: Phase 1  
**存储**: MariaDB + 本地缓存

```sql
CREATE TABLE agent_prompt_template (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    template_key    VARCHAR(64)  NOT NULL COMMENT '模板键名（如 system.default, system.with_tools）',
    template_content TEXT        NOT NULL COMMENT '模板内容，支持 {placeholder} 变量替换',
    description     VARCHAR(255) DEFAULT NULL COMMENT '模板说明',
    version         INT          NOT NULL DEFAULT 1 COMMENT '版本号',
    is_active       TINYINT      NOT NULL DEFAULT 1 COMMENT '是否启用',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    UNIQUE INDEX idx_template_key_version (template_key, version)
) COMMENT 'AI 提示词模板';
```

**变量替换机制**:

```java
@Component
public class PromptTemplateRenderer {

    @Cacheable("promptTemplates")  // 本地缓存，避免每次请求查 DB
    public String render(String templateKey, Map<String, String> variables) {
        String template = promptTemplateMapper.findByKey(templateKey);
        // 替换 {userName}, {city}, {tools} 等占位符
        for (Map.Entry<String, String> entry : variables.entrySet()) {
            template = template.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        return template;
    }
}
```

**模板示例**:

```text
你是探点的智能助手，名叫"小黑助手"。

当前用户：{userName}（{city}）
当前时间：{datetime}

可用工具：
{tools}

行为准则：
1. 每次最多调用一个工具
2. 调用工具前先思考并告知用户
3. 工具返回结果后，用自然语言回复用户
4. 如果工具返回空结果，提供替代建议
```

#### 3.3.7 用户偏好表 — `agent_user_preference`

**对应缺失**: P2 — 用户偏好  
**实现阶段**: Phase 3/5  
**存储**: Redis（活跃偏好）+ MariaDB（持久化）

```sql
CREATE TABLE agent_user_preference (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id         BIGINT       NOT NULL COMMENT '用户 ID',
    pref_key        VARCHAR(64)  NOT NULL COMMENT '偏好键（如 food_preference, price_range）',
    pref_value      VARCHAR(255) NOT NULL COMMENT '偏好值',
    source          VARCHAR(32)  DEFAULT 'auto' COMMENT 'auto-自动学习 manual-用户手动设置',
    confidence      TINYINT      DEFAULT 50 COMMENT '置信度 0-100',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    UNIQUE INDEX idx_user_key (user_id, pref_key),
    INDEX idx_user_id (user_id)
) COMMENT '用户偏好设置';

-- Redis 缓存结构（用于运行时快速读取）:
-- Hash: agent:pref:{userId}
-- Field: food_preference → "川菜,火锅"
-- Field: price_range → "50-150"
-- TTL: 7 天（活跃用户自动续期）
```

#### 3.3.8 工具注册表 — `agent_tool_registry`

**对应缺失**: P2 — 工具注册元数据  
**实现阶段**: Phase 2  
**存储**: 内存（启动时扫描）+ 可选持久化

```java
// 内存注册表（启动时由 ToolBeanCollector 填充）
@Component
public class ToolRegistry {
    
    private final Map<String, ToolRegistration> tools = new ConcurrentHashMap<>();

    @Data
    @Builder
    public static class ToolRegistration {
        private String name;
        private String description;
        private boolean active;
        private List<ParamInfo> parameters;
        private String permissionResource;
        private DataAction permissionAction;
    }

    @Data
    @Builder
    public static class ParamInfo {
        private String name;
        private String description;
        private boolean required;
        private String type;  // String, Long, Boolean...
    }

    public List<ToolRegistration> listActiveTools() {
        return tools.values().stream()
            .filter(ToolRegistration::isActive)
            .collect(Collectors.toList());
    }

    /** 生成给 LLM 的工具描述文本 */
    public String generateToolsDescription() {
        return listActiveTools().stream()
            .map(t -> String.format("- %s(%s): %s",
                t.getName(),
                t.getParameters().stream()
                    .map(p -> p.getName() + (p.isRequired() ? "*" : ""))
                    .collect(Collectors.joining(", ")),
                t.getDescription()))
            .collect(Collectors.joining("\n"));
    }
}
```

**与 `ToolBeanCollector` 的关系**:

```
ToolBeanCollector（启动时扫描 @TargetTool Bean）
  ↓ 调用 ToolCallbacks.from(bean)
  ↓ 提取 @Tool 方法信息
  ↓
ToolRegistry.register(name, description, params, permission)
  ↓
GuardedToolCallback 包装时从 ToolRegistry 获取守卫元数据
AgentConfig 构建 system prompt 时从 ToolRegistry 生成工具列表
```

---

### 3.4 各阶段的数据结构演进路线

不是所有表都要在第一周建好。以下是按 Phase 分批的实施计划：

```
Phase 0  不涉及数据模型变更（纯清理）
  │
Phase 1  ─── 新增 4 个核心表
  │         ├── agent_conversation    ← 会话元数据（P0 缺口）
  │         ├── agent_approval        ← 审批记录（P1 缺口）
  │         ├── agent_prompt_template ← 提示词模板（P1 缺口）
  │         └── agent_message         ← 消息明细（P0 缺口）
  │
Phase 2  ─── 新增 2 个结构 + 1 个协议
  │         ├── SSE 事件协议定义      ← 有线格式标准化
  │         ├── agent_trace           ← 执行轨迹
  │         └── ToolRegistry          ← 内存工具注册表
  │
Phase 3  ─── 新增 1 个表
  │         └── agent_user_preference ← 用户偏好
  │
Phase 4  ─── 存储升级
  │         ├── chat_memory JDBC → Redis
  │         ├── agent_trace → Elasticsearch（可选）
  │         └── 所有表的 TTL 清理策略上线
  │
Phase 5  ─── 智能数据结构
            ├── 向量数据库集合（RAG 知识库）
            └── 知识图谱节点（长期记忆关联）
```

---

## 4. Phase 0 — 清理与加固（1-2 天）

> 注意：本节及后续章节编号已从原始路线图 +1，因新增了第 3 节"数据模型现状与重构"。

> **Phase 0 验收状态: ✅ 全部完成 (2026-07-22)**  
> 以下 6 项任务均已实施，代码审查通过。

**目标**: 消除死代码、修复已知缺陷，让现有系统健康运行。

### 4.1 删除死代码

#### ✅ Task 0.1 — 删除 `PromptGuard.java`

**文件**: ~~`picknear/src/main/java/com/hmdp/agent/tool/PromptGuard.java`~~（已删除）

**验收结果**: 源文件已不存在，target 中的 `.class` 残留不影响运行，下次 `mvn clean` 后自动清除。

#### ✅ Task 0.2 — 删除空端点

**文件**: ~~`picknear/src/main/java/com/hmdp/agent/controller/ChatController.java:103-108`~~（已删除）

**验收结果**: `postMethodName()` 空方法已被移除，文件末尾于 `chat()` 方法正确结束后闭合。

#### ✅ Task 0.3 — 统一代码注释风格

**验收结果**: `PromptGuard.java` 已删除，其设计笔记同步清理。其余文件（ChatController、AiServiceImpl、AgentConfig）的注释均已采用规范的 Javadoc 风格，无设计草稿残留。

### 4.2 修复已知缺陷

#### ✅ Task 0.4 — 配置 AI 专用线程池

**验收结果**:
- `AgentConfig.java` 已配置 `aiTaskExecutor` Bean（core=2, max=4, 队列=100, CallerRunsPolicy, 前缀 `ai-worker-`）
- `AiServiceImpl.java` 已通过 `@Resource(name = "aiTaskExecutor")` 注入
- `CompletableFuture.runAsync(..., aiTaskExecutor)` 已替换默认的 `ForkJoinPool.commonPool()`

#### ✅ Task 0.5 — SSE 错误推送避免 JSON 注入

**验收结果**: `AiServiceImpl.java:102` 已使用 `escapeJson(hookResult.getReason())`，特殊字符被正确转义。

#### ✅ Task 0.6 — SSE 推送 conversationId 的错误处理

**验收结果**: `ChatController.java:82-86` 已实现 `completeWithError(e)` + `return null`，推送失败时及时终止请求。

### 4.3 Phase 0 验收标准

- [x] `PromptGuard.java` 已删除
- [x] 空端点 `postMethodName` 已删除
- [x] AI 专用线程池已配置并注入
- [x] SSE JSON 注入漏洞已修复
- [x] SSE conversationId 推送失败时及时终止
- [x] 项目可正常编译、启动

> **验收结论**: Phase 0 全部 6 项任务已完成。代码已清理，已知缺陷已修复，系统具备继续迭代的基础。

---

## 5. Phase 1 — 基础设施补全（3-5 天）

**目标**: 补测试、补监控、补配置管理，让项目具备可维护性。

### 5.1 测试体系

#### 🔧 Task 1.1 — Guard 层单元测试

`ToolGuardManager` 和 4 个 `ToolGuardPolicy` 是纯逻辑，最适合单元测试。

**测试目标**:

| 测试类 | 测试点 | 示例 |
|--------|--------|------|
| `HighRiskListPolicyTest` | 工具名在黑名单中 → BLOCK | `deleteBlog` → BLOCK |
| | 工具名不在黑名单中 → ABSTAIN | `queryBlog` → ABSTAIN |
| | 黑名单为空 → ABSTAIN | |
| `ConfirmToolPolicyTest` | 工具名在确认名单中 → CONFIRM | `publishTestBlog` → CONFIRM |
| | 工具名不在确认名单中 → ABSTAIN | |
| `PatternMatchPolicyTest` | 正则匹配工具名 → BLOCK/CONFIRM | `.*delete.*` → BLOCK |
| | 正则匹配参数 → BLOCK/CONFIRM | |
| | 不匹配 → ABSTAIN | |
| `RateLimitPolicyTest` | 首次调用 → ABSTAIN | |
| | 超过阈值 → BLOCK | 模拟 Redis 计数器 |
| | Redis 异常 → 降级 ABSTAIN | |
| `ToolGuardManagerTest` | 任一 BLOCK → 最终 BLOCK | 一票否决 |
| | 无 BLOCK + CONFIRM → 最终 CONFIRM | |
| | 全部 ALLOW/ABSTAIN → 最终 ALLOW | |
| | 异常策略跳过，不影响决策 | Fail-Open |

**建议框架**: JUnit 5 + Mockito（项目已有这些依赖）

#### 🔧 Task 1.2 — GuardedToolCallback 单元测试

**测试目标**:
- BLOCK 返回错误字符串
- CONFIRM 返回确认提示
- ALLOW 委托给原始 callback
- `returnDirect` 参数影响返回值格式
- `ToolContext` 中的 userId 覆盖构造函数传入的 userId

#### 🔧 Task 1.3 — PromptHookChain 单元测试

**测试目标**:
- 全部 PASS → 返回 PASS
- 任一 REPLACE → 替换生效，后续 Hook 用替换文本
- 任一 BLOCK → 立即短路
- 异常 Hook → Fail-Open 降级 PASS

#### 🔧 Task 1.4 — AiServiceImpl 集成测试（可选但推荐）

使用 Mock DashScope API 或 WireMock：
- 验证 Hook 链在同步模式中正确执行
- 验证 SSE 模式推送格式正确
- 验证工具调用结果正确返回

### 5.2 CONFIRM 审批机制落地

#### 🔧 Task 1.5 — 实现真实 CONFIRM 机制

这是 Phase 1 最重要的功能。当前 CONFIRM 只返回字符串给 LLM，没有真正暂停。

**方案对比**:

| 方案 | 复杂度 | 用户体验 | 推荐 |
|------|--------|---------|------|
| **A: 降级为 BLOCK** | ⭐ 极低 | 所有需确认操作被拒，用户找客服 | ❌ 太粗暴 |
| **B: 推送到前端确认队列** | ⭐⭐⭐ 中 | SSE 或 WebSocket 发确认事件，前端弹窗 | ✅ 最佳 |
| **C: 确认令牌（Confirm Token）** | ⭐⭐ 低 | 后端生成确认码，用户通过二次请求确认 | ✅ 次选 |

**推荐方案 B**:

```
用户: "帮我发一篇博客"
  ↓
Agent → Guard CONFIRM
  ↓
SSE 推送: data: {"type":"confirm","id":"cfm_xxx","tool":"publishTestBlog","reason":"需要确认"}
  ↓ (前端弹确认对话框，用户点击"确认")
前端: POST /agent/confirm { confirmId: "cfm_xxx" }
  ↓
后端: 确认队列标记为已确认
  ↓ (GuardedToolCallback 重试或继续)
工具执行 → 结果推 SSE
```

**简化实现（方案 C 变体）**:

如果不想引入 WebSocket，可以直接让 LLM 返回确认提示，用户通过文本确认：

```
LLM: "您确认要发布一篇测试博客吗？回复"确认发布"继续"
用户: "确认发布"
  ↓
AiServiceImpl 检测到用户确认意图
  ↓
调用 toolBeanCollector.getToolCallback("publishTestBlog").call(...)
```

这需要：
1. 在 `AiServiceImpl` 中增加确认意图检测逻辑
2. `ToolBeanCollector` 提供按名称获取 ToolCallback 的方法
3. 会话级暂存待确认的工具调用上下文

**最低成本方案**: 如果以上都太复杂，先把所有 CONFIRM 策略改为 BLOCK，并在 BLOCK 消息中引导用户联系管理员。安全优先。

### 5.3 配置外置

#### 🔧 Task 1.6 — 系统提示词外置

**当前**: 硬编码在 `AgentConfig.java:49`

```java
.defaultSystem("你是电商客服，但当用户问天气时，你必须调用 queryWeather 工具，不要自己回答。")
```

**目标**: 从配置文件或数据库加载

```yaml
hmdp:
  agent:
    system-prompt: |
      你是探点的智能助手，名叫"小黑助手"。
      
      核心能力：
      - 店铺查询、推荐
      - 优惠券查询
      - 天气查询（调用 queryWeather 工具）
      
      行为准则：
      - 当你需要使用工具时，必须先说明你要做什么
      - 如果用户没有明确需求，主动引导对话
```

同时支持动态注入上下文：

```java
.defaultSystem(systemPromptBuilder
    .withUserName(user.getName())
    .withCity(user.getCity())
    .withDateTime(LocalDateTime.now())
    .build())
```

### 5.4 Phase 1 验收标准

- [ ] ToolGuardManager + 所有 Policy 有单元测试，覆盖率 > 90%
- [ ] GuardedToolCallback 有单元测试
- [ ] PromptHookChain 有单元测试
- [ ] CONFIRM 不再是空壳（实现了方案 B/C 或降级为 BLOCK）
- [ ] 系统提示词已外置到配置
- [ ] 测试在 CI 中通过

---

## 6. Phase 2 — 从 Function Calling 到真正 Agent（1-2 周）

**目标**: 让 Agent 具备多步推理能力，从"调一次工具就完事"进化为"能规划、能迭代、能自主决策"。

### 6.1 核心概念

**当前模式（Function Calling）**:

```
用户输入 → LLM(可能调工具) → 返回结果
```

**目标模式（Agent）**:

```
用户输入 → LLM(思考 → 行动 → 观察 → 思考 → 行动 → ... → 综合 → 回复)
```

### 6.2 技术选型

Spring AI 1.1.2 支持以下 Agent 模式：

| 模式 | Spring AI 支持 | 复杂度 | 适用场景 |
|------|---------------|--------|---------|
| **ReAct** | `ChatClient` + 工具循环 | ⭐⭐ | 通用多步推理，推荐首选 |
| **Plan-and-Execute** | 自定义实现 | ⭐⭐⭐ | 复杂任务分解 |
| **Multi-Agent** | 自定义实现 | ⭐⭐⭐⭐ | 分工协作（远期） |

**推荐**: 先做 **ReAct**，这是最成熟的 Agent 模式。

### 6.3 ReAct 实现方案

#### ReAct 循环流程

```
循环直到满足结束条件:
  1. THOUGHT: LLM 输出推理过程（"用户想吃辣，我需要查川菜馆"）
  2. ACTION: LLM 决定调用工具（queryShopByType("川菜")）
  3. OBSERVATION: 工具返回结果（[店铺A, 店铺B, ...]）
  4. 回到 1
  5. FINAL ANSWER: LLM 输出最终回复
```

#### 🔧 Task 2.1 — 实现 ReAct Agent 基类

```java
// agent/service/ReActAgent.java
@Component
@Slf4j
public class ReActAgent {

    private final ChatClient chatClient;
    private final int maxIterations = 5;  // 防死循环

    public AgentResult execute(String userInput, String conversationId, Map<String, Object> toolContext) {
        String thought = "";
        String observation = "";
        int iteration = 0;

        while (iteration < maxIterations) {
            iteration++;

            // 1. 构建 ReAct prompt（包含之前的 thought、action、observation）
            String prompt = buildReActPrompt(userInput, thought, observation);

            // 2. 调用 LLM（带工具）
            String response = chatClient.prompt()
                    .user(prompt)
                    .toolContext(toolContext)
                    .call()
                    .content();

            // 3. 解析响应
            if (isFinalAnswer(response)) {
                return AgentResult.success(extractFinalAnswer(response), iteration);
            }

            // 4. 提取 Thought 和 Action（工具由 Spring AI SDK 自动执行）
            thought = extractThought(response);
            // observation 由工具调用结果自动返回
        }

        return AgentResult.maxIterationsReached(maxIterations);
    }
}
```

**关键设计决策**:

| 决策 | 选项 | 选择 | 理由 |
|------|------|------|------|
| 循环控制 | LLM 自主 vs 代码强制 | 代码强制 `maxIterations` | 防止 Token 爆炸和无限循环 |
| 状态传递 | ChatMemory vs 手动拼接 | 手动拼接 ReAct 格式 prompt | ChatMemory 是用户视角，ReAct 需要独立的推理轨迹 |
| 工具执行 | Spring AI 自动 vs 手动 | Spring AI 自动 | SDK 已处理工具调用逻辑，无需重复造轮子 |

#### 🔧 Task 2.2 — 改造 AiServiceImpl

**当前**: 一个 `chatReturnStringResult()` 处理所有。

**改造后**:

```java
@Service
public class AiServiceImpl implements AiService {

    @Resource
    private ReActAgent reactAgent;  // 新增

    @Override
    public String chatReturnStringResult(String content, String conversationId) {
        // Hook 链逻辑保持不变
        ChatContext ctx = ...;
        HookResult hookResult = promptHookChain.execute(content, ctx);
        String finalContent = processHookResult(hookResult, content, conversationId);
        if (finalContent == null) return "❌ " + hookResult.getReason();

        // 使用 ReAct Agent 替代直接 call()
        AgentResult result = reactAgent.execute(finalContent, conversationId, toolContext);
        return result.getContent();
    }

    @Override
    public void chatWithToolcall(String content, String conversationId, SseEmitter emitter) {
        // 类似改造，异步执行 ReAct Agent
    }
}
```

#### 🔧 Task 2.3 — SSE 模式支持 ReAct 中间状态推送

改造流式推送，不仅推送最终结果，还推送推理过程：

```
data: {"type":"thought","content":"用户想吃辣，我需要查询川菜馆"}
data: {"type":"tool_call","tool":"queryShopByType","args":"川菜"}
data: {"type":"tool_result","tool":"queryShopByType","result":"找到 3 家店铺"}
data: {"type":"thought","content":"找到了3家川菜馆，让我推荐评分最高的"}
data: {"type":"text","content":"为您推荐以下川菜馆：\n1. 辣有道川菜馆 ⭐4.8\n..."}
data: {"type":"done"}
```

前端可以：
- `thought` 类型 → 显示"思考中..."动效
- `tool_call` 类型 → 显示"正在查询..."提示
- `text` 类型 → 追加到对话气泡

**实现方式**:

当前是"伪流式"（一次性阻塞调用），升级为真流式 + 事件类型。

#### 🔧 Task 2.4 — 真流式升级

**文件**: `AiServiceImpl.java` 的 `chatWithToolcall()`

**现状**: 使用 `call().content()` 阻塞等待完整结果。

**目标**: 使用 `stream()` 逐 chunk 推送。

```java
chatClient.prompt()
    .user(finalContent)
    .toolContext(toolContext)
    .stream()
    .chatClientResponse()
    .subscribe(
        response -> {
            // 提取文本 chunk → push to SSE
            if (response.getText() != null) {
                emitter.send(SseEmitter.event()
                    .data("{\"type\":\"text\",\"content\":\"" + escapeJson(response.getText()) + "\"}"));
            }
            // 提取工具调用 → push to SSE
            if (response.getToolCalls() != null && !response.getToolCalls().isEmpty()) {
                for (ToolCall tc : response.getToolCalls()) {
                    emitter.send(SseEmitter.event()
                        .data("{\"type\":\"tool_call\",\"tool\":\"" + tc.getName() + "\"}"));
                }
            }
        },
        error -> { /* 错误处理 */ },
        () -> {
            emitter.send(SseEmitter.event().data("{\"type\":\"done\"}"));
            emitter.complete();
        }
    );
```

**注意事项**: Spring AI 1.1.2 的流式 + 工具调用有已知复杂性（text chunk 和 tool call 穿插），需要充分测试。

### 6.4 Phase 2 验收标准

- [ ] ReAct Agent 基类实现，支持多步推理循环
- [ ] `maxIterations` 防死循环机制生效
- [ ] AiServiceImpl 接入 ReAct Agent（向后兼容）
- [ ] SSE 模式推送推理中间状态（thought/tool_call/text/error/done）
- [ ] 真流式替代伪流式
- [ ] 有 ReAct Agent 的单元测试（Mock LLM 返回不同阶段响应）
- [ ] 现有功能全部回归通过

---

## 7. Phase 3 — 业务工具生态（2-3 周）

**目标**: 丰富工具生态，让 Agent 真正能处理核心业务。

### 7.1 工具优先级矩阵

| 工具 | 优先级 | 复杂度 | 业务价值 | 依赖 |
|------|--------|--------|---------|------|
| `ShopQueryTool` | P0 | ⭐⭐ | 极高 — 核心业务 | IShopService |
| `VoucherQueryTool` | P0 | ⭐⭐ | 极高 — 核心业务 | IVoucherService |
| `ShopRecommendTool` | P0 | ⭐⭐⭐ | 极高 — 差异化能力 | 评分 + 距离 + 偏好算法 |
| `OrderQueryTool` | P1 | ⭐⭐ | 高 — 用户高频需求 | IOrderService |
| `UserProfileTool` | P1 | ⭐ | 中 — 辅助工具 | IUserService |
| `MapQueryTool` | P2 | ⭐⭐⭐ | 中 — 体验提升 | 地图 API |
| `CouponTool` | P2 | ⭐⭐ | 中 — 营销场景 | IVoucherService |

### 7.2 工具实现规范

每个工具类必须遵循以下规范：

```java
@TargetTool(active = true)
@Slf4j
public class ShopQueryTool {

    @Resource
    private IShopService shopService;

    @Tool(description = "根据店铺名称模糊搜索店铺，返回匹配的店铺列表")
    public List<Shop> searchShops(
            @ToolParam(description = "搜索关键词，如'川菜'、'海底捞'") String keyword,
            @ToolParam(description = "城市名称（可选，不传则全国搜索）", required = false) String city,
            ToolContext toolContext) {
        Long userId = (Long) toolContext.getContext().get("userId");
        log.info("searchShops: keyword={}, city={}, userId={}", keyword, city, userId);
        // 实现搜索逻辑
    }

    @Tool(description = "根据评分和距离推荐店铺，返回排序后的店铺列表")
    @RequiredDataPermission(resource = "shop", action = DataAction.READ)
    public List<Shop> recommendShops(
            @ToolParam(description = "店铺类型ID（可选）") Long typeId,
            @ToolParam(description = "排序方式: score-评分优先, distance-距离优先") String sortBy,
            @ToolParam(description = "返回数量，最多20条") int limit,
            ToolContext toolContext) {
        // 实现推荐逻辑
    }
}
```

### 7.3 工具设计原则

| 原则 | 说明 | 反例 | 正例 |
|------|------|------|------|
| **参数具体化** | 参数用具体类型，不用 Map | `Map<String, Object> args` | `String keyword, Long typeId` |
| **描述语义化** | @Tool/@ToolParam 描述让 LLM 理解何时调用 | `查询店铺` | `根据...搜索店铺，当用户提到...时调用` |
| **上下文传递** | 用户信息走 ToolContext | 直接从 UserHolder 取 | `ToolContext` 参数注入 |
| **权限标注** | 操作他人数据需 `@RequiredDataPermission` | 裸调 Service | 注解声明资源 + 操作 |
| **返回精简** | 返回 LLM 够用的最小字段集 | 返回完整 Entity | 返回 DTO 或精简列表 |
| **幂等设计** | 查询幂等，写入考虑重试 | 每次调用副作用不同 | 幂等 key 或唯一约束 |

### 7.4 工具之间的协作模式

Agent 需要多个工具协作完成复杂任务：

**场景示例**: "帮我找离我最近的川菜馆，看看有没有优惠券"

```
Agent 推理过程:
  THOUGHT: 用户要找川菜馆，需要先查店铺类型
  ACTION: queryShopType("川菜") → typeId=1
  OBSERVATION: 川菜类型 ID 为 1

  THOUGHT: 知道类型了，按距离排序查店铺
  ACTION: queryShopsByType(typeId=1, sortBy="distance", limit=5)
  OBSERVATION: [辣有道川菜馆(1.2km), 蜀味轩(0.8km), ...]

  THOUGHT: 找到了店铺，看看有没有优惠券
  ACTION: queryVouchersByShopId(shopId=2)
  OBSERVATION: [满100减20, 新客8折]

  FINAL ANSWER: "为您推荐以下川菜馆：\n1. 蜀味轩 ⭐4.6 (0.8km) 有满100减20优惠券\n2. ..."
```

这需要 **多个工具串行调用**，也就是 Phase 2 的 ReAct 能力 + Phase 3 的工具生态一起发挥作用。

### 7.5 工具测试

每个工具必须有：

```java
@SpringBootTest
class ShopQueryToolTest {

    @MockBean
    private IShopService shopService;

    @Autowired
    private ShopQueryTool shopQueryTool;

    @Test
    void searchShops_shouldReturnResults() {
        // 准备 mock 数据
        when(shopService.search(any())).thenReturn(List.of(shopA, shopB));

        // 执行
        List<Shop> result = shopQueryTool.searchShops("川菜", "北京", mockContext);

        // 验证
        assertThat(result).hasSize(2);
        verify(shopService).search(any());
    }

    @Test
    void searchShops_shouldSupportEmptyKeyword() {
        List<Shop> result = shopQueryTool.searchShops("", "北京", mockContext);
        assertThat(result).isEmpty();
    }
}
```

### 7.6 系统提示词升级

工具多了之后，系统提示词需要动态构造，让 LLM 知道每个工具的用途和调用场景：

```yaml
hmdp:
  agent:
    system-prompt: |
      你是一个智能助手，帮助用户完成以下操作：
      
      ## 可用工具
      {tools_description}    # 由系统自动生成
      
      ## 行为准则
      1. 每次最多调用一个工具
      2. 工具返回结果后，根据结果决定下一步
      3. 如果工具返回空结果，告诉用户并提供替代建议
      4. 如果需要多个信息，分步调用工具而非一次完成
```

`{tools_description}` 由 `ToolBeanCollector` 在启动时生成：

```java
String toolsDescription = Arrays.stream(toolCallbacks)
    .map(tc -> String.format("- %s: %s", 
        tc.getToolDefinition().name(), 
        tc.getToolDefinition().description()))
    .collect(Collectors.joining("\n"));
```

### 7.7 Phase 3 验收标准

- [ ] ShopQueryTool 实现（搜索 + 推荐）
- [ ] VoucherQueryTool 实现
- [ ] 至少 5 个活跃业务工具
- [ ] WeatherQueryTool 不删除但标记 `active = false`（它只是 Demo）
- [ ] 每个工具有单元测试，覆盖正常和异常路径
- [ ] 系统提示词动态构造
- [ ] 端到端测试："找川菜馆看优惠券"场景走通（ReAct + 多工具协作）

---

## 8. Phase 4 — 生产级能力（1-2 月）

**目标**: 让 Agent 模块达到生产部署标准。

### 8.1 可观测性

#### 🔧 Task 4.1 — AI 调用链路追踪

```yaml
# application.yml
management:
  tracing:
    enabled: true
    sampling.probability: 1.0  # AI 链路 100% 采样
```

**关键指标**:

| 指标 | 类型 | 维度 | 目标 |
|------|------|------|------|
| `agent.invoke.total` | Counter | mode(json/sse), model, status(success/error) | 监控调用量 |
| `agent.invoke.duration` | Histogram | mode, toolCount | P99 < 10s |
| `agent.tool.invoke.total` | Counter | toolName, decision(allow/block/confirm) | 工具调用分布 |
| `agent.tool.duration` | Histogram | toolName | P99 < 2s |
| `agent.hook.result` | Counter | hookName, decision(pass/block/replace) | Hook 命中统计 |
| `agent.guard.vote` | Counter | policyName, vote(allow/block/confirm/abstain) | 守卫策略效果 |

可以用 Micrometer（Spring Boot 自带）暴露到 Prometheus + Grafana。

#### 🔧 Task 4.2 — Token 消耗统计

```java
@Component
public class TokenUsageTracker {

    private final MeterRegistry meterRegistry;
    private final Map<String, AtomicLong> tokenCounters = new ConcurrentHashMap<>();

    public void track(String model, int inputTokens, int outputTokens) {
        meterRegistry.counter("agent.token.input", "model", model).increment(inputTokens);
        meterRegistry.counter("agent.token.output", "model", model).increment(outputTokens);
        // 费用估算
        double cost = estimateCost(model, inputTokens, outputTokens);
        meterRegistry.counter("agent.cost", "model", model).increment(cost);
    }
}
```

### 8.2 对话记忆升级

#### 🔧 Task 4.3 — JDBC → Redis 迁移

**当前**: `JdbcChatMemoryRepository` + `MessageWindowChatMemory(maxMessages=10)`

**问题**:
- JDBC 存储对话历史，高频读写性能差
- 固定窗口 10 条，长对话会被截断
- 重启后历史丢失风险（取决于 JDBC 配置）

**目标**: `RedisChatMemory` + 摘要压缩

```java
@Bean
public ChatMemory chatMemory(RedisTemplate<String, Object> redisTemplate) {
    // Redis 存储 + 摘要策略
    return new SummarizingChatMemory(
        RedisChatMemory.builder()
            .redisTemplate(redisTemplate)
            .ttl(Duration.ofHours(24))
            .build(),
        new TokenBasedSummarizer(chatClient, maxTokens: 1000)
    );
}
```

**摘要策略**: 当消息数超过 N 条或 Token 数超过 M 时：
1. 将最旧的一半消息压缩为一段摘要
2. 保留最新 N/2 条完整消息
3. 每次请求时：`[摘要] + [最近完整消息]`

### 8.3 错误处理与容错

#### 🔧 Task 4.4 — 分级降级策略

| 故障场景 | 降级行为 | 用户感知 |
|---------|---------|---------|
| LLM API 超时 | 重试 1 次后返回"服务繁忙" | 稍后重试 |
| 工具调用异常（如 DB 宕机） | 跳过该工具，LLM 生成替代回复 | "暂时查不到" |
| Guard Redis 不可用 | 降级为全部 ABSTAIN（不拦截） | 无（风险接受） |
| Hook 异常 | Fail-Open 降级 PASS | 无 |
| ChatMemory 不可用 | 降级为无状态对话 | 无多轮记忆 |

#### 🔧 Task 4.5 — 客户端断连清理

SSE 模式下，客户端断开连接时：
- `SseEmitter` 抛 `IOException`
- 需要取消正在进行的 AI 调用（释放 Token 配额）
- 清理异步线程

```java
emitter.onError(ex -> {
    if (ex instanceof IOException) {
        log.info("客户端断开连接，取消 AI 调用");
        // 取消 CompletableFuture
        future.cancel(true);
    }
});
```

### 8.4 性能优化

#### 🔧 Task 4.6 — 连接池监控

DashScope HTTP 连接池需要监控：

```java
@Component
public class ConnectionPoolMonitor {

    private final PoolingHttpClientConnectionManager syncPool;
    private final ConnectionProvider metricsProvider;

    @Scheduled(fixedRate = 30000)
    public void report() {
        // 同步连接池
        log.info("Sync pool: total={}, leased={}, pending={}",
            syncPool.getTotalStats().getMax(),
            syncPool.getTotalStats().getLeased(),
            syncPool.getTotalStats().getPending());

        // 流式连接池（Reactor Netty 自带 Micrometer 集成）
    }
}
```

#### 🔧 Task 4.7 — 响应缓存

对于确定性查询（如店铺信息），设置短 TTL 缓存：

```java
@Tool(description = "根据店铺ID查询店铺详细信息")
public Shop queryShopById(@ToolParam(description = "店铺ID") Long id, ToolContext ctx) {
    String cacheKey = "agent:shop:" + id;
    Shop cached = cacheManager.getCache(cacheKey);
    if (cached != null) return cached;

    Shop shop = shopService.getById(id);
    cacheManager.put(cacheKey, shop, Duration.ofMinutes(5));  // 店铺数据可缓存 5 分钟
    return shop;
}
```

### 8.5 Phase 4 验收标准

- [ ] Prometheus + Grafana 监控面板就绪（调用量、延迟、Token 消耗、错误率）
- [ ] Redis ChatMemory 上线且摘要压缩生效
- [ ] 所有外部依赖故障都有降级路径
- [ ] 客户端断连时 AI 调用及时取消
- [ ] 连接池监控 7×24 运行
- [ ] 响应缓存上线
- [ ] 性能测试：持续 30 分钟 100 QPS 不崩溃，P99 延迟 < 15s

---

## 9. Phase 5 — 智能进化（远期）

**目标**: 让 Agent 从"能用"到"好用"再到"聪明"。

### 9.1 RAG 知识库

**场景**: 用户问"你们有哪些优惠活动？" 或 "这家店评分怎么样？"

**现在**: LLM 只能基于训练数据回答，不知道实时活动信息。

**RAG 后**:

```
用户: "最近有什么优惠活动？"
  ↓
Agent: 检索知识库（店铺活动、优惠券信息）
  ↓
      ┌─ 向量化用户问题
      ├─ 语义搜索相似文档
      └─ 将结果作为 context 注入 LLM
  ↓
Agent: "最近的优惠活动有：1. 海底捞满200减50（到8月底）2. ..."
```

**技术选型**: 
- 向量数据库: Redis Stack（项目已有 Redis）或 Elasticsearch
- Embedding: DashScope 的 text-embedding 系列
- 摄取管道: 定时任务同步店铺、活动信息到向量库

### 9.2 长期记忆

**场景**: 用户上次说"我对辣的感兴趣"，下次回来 Agent 记得。

**现在**: 每次对话独立，无跨会话记忆。

**长期记忆后**:

```java
@Component
public class LongTermMemory {

    private final StringRedisTemplate redisTemplate;

    /** 存储用户偏好 */
    public void remember(Long userId, String key, String value) {
        redisTemplate.opsForHash().put("memory:user:" + userId, key, value);
    }

    /** 在对话开始时注入系统提示词 */
    public String buildUserProfile(Long userId) {
        Map<Object, Object> prefs = redisTemplate.opsForHash().entries("memory:user:" + userId);
        if (prefs.isEmpty()) return "";

        return "用户已知偏好：" + prefs.entrySet().stream()
            .map(e -> e.getKey() + "=" + e.getValue())
            .collect(Collectors.joining("; "));
    }
}
```

### 9.3 多 Agent 协作

**场景**: 一个 Agent 处理推荐，一个 Agent 处理订单，一个 Agent 处理售后。

分工：

| Agent | 职责 | 工具集 |
|-------|------|--------|
| **推荐 Agent** | 店铺推荐、优惠推荐 | ShopQueryTool, VoucherQueryTool |
| **服务 Agent** | 订单查询、售后 | OrderTool, RefundTool |
| **导购 Agent** | 闲聊、需求引导 | 通用对话能力 |

协作方式：**Orchestrator Agent** 接收用户输入，分派给专业 Agent，汇总结果。

### 9.4 持续学习

- **用户反馈回路**: 用户对推荐结果点赞/差评 → 调整推荐权重
- **工具调用分析**: 哪些工具被频繁调用？哪些调用模式暴露了工具设计缺陷？
- **误拦截分析**: Guard 拦截中有多少是误杀？定期 review 优化策略

### 9.5 Phase 5 验收标准

- [ ] RAG 知识库上线，覆盖店铺信息、优惠活动
- [ ] 长期记忆让跨会话偏好传递
- [ ] 多 Agent 协作基座就绪（至少 2 个专业 Agent）
- [ ] 用户反馈闭环上线

---

## 10. 附录：每个阶段的可交付物清单

### Phase 0 交付物

| 产出 | 类型 | 说明 |
|------|------|------|
| 代码清理 Commit | 代码 | 删除死代码、空方法 |
| 线程池配置 | 代码 | AI 专用线程池 |
| SSE 安全修复 | 代码 | JSON 注入修复 |
| 更新架构文档 | 文档 | 同步最新代码状态 |

### Phase 1 交付物

| 产出 | 类型 | 说明 |
|------|------|------|
| `promptguard/*Test.java` | 测试 | Guard 全部单元测试 |
| `GuardedToolCallbackTest.java` | 测试 | 代理模式单元测试 |
| `PromptHookChainTest.java` | 测试 | 链式执行器单元测试 |
| CONFIRM 实现 | 代码 | 确认机制落地 |
| 提示词外置 | 代码 | YAML 配置加载 |

### Phase 2 交付物

| 产出 | 类型 | 说明 |
|------|------|------|
| `ReActAgent.java` | 代码 | Agent 基类 |
| `AgentResult.java` | 代码 | Agent 执行结果 |
| 改造后的 `AiServiceImpl` | 代码 | 接入 ReAct |
| SSE 事件类型定义 | 文档 | type 枚举 |
| SSE 真流式实现 | 代码 | `stream()` 替代 `call()` |
| `ReActAgentTest.java` | 测试 | 模拟多步推理 |

### Phase 3 交付物

| 产出 | 类型 | 说明 |
|------|------|------|
| `ShopQueryTool.java` | 代码 | 搜索 + 推荐 |
| `VoucherQueryTool.java` | 代码 | 优惠券查询 |
| `OrderQueryTool.java` | 代码 | 订单查询 |
| 工具单元测试 N 个 | 测试 | 每个工具 1 个测试类 |
| 端到端集成测试 | 测试 | "找餐厅→看优惠"场景 |

### Phase 4 交付物

| 产出 | 类型 | 说明 |
|------|------|------|
| Micrometer 指标定义 | 代码 | Counter + Histogram |
| Grafana Dashboard JSON | 配置 | AI 监控面板 |
| Redis ChatMemory | 代码 | 会话存储升级 |
| 降级策略文档 | 文档 | 故障响应手册 |
| 性能测试报告 | 文档 | 100 QPS 压测结果 |

### Phase 5 交付物

| 产出 | 类型 | 说明 |
|------|------|------|
| RAG 管道 | 代码 | 数据摄取 + 语义检索 |
| 长期记忆实现 | 代码 | 用户偏好持久化 |
| 多 Agent 框架 | 代码 | Orchestrator + Specialists |
| A/B 测试框架 | 代码 | Prompt 效果对比 |

---

## 附：阶段投入估算

```
Phase 0  ██░░░░░░░░    1-2 天     🎯 立刻开始
Phase 1  ██████░░░░    3-5 天     🎯 立刻开始
Phase 2  ██████████░   1-2 周     核心质变
Phase 3  ██████████░   2-3 周     业务价值
Phase 4  ████████████  1-2 月     生产标准
Phase 5  ████████████  2-3 月     持续迭代
```

**建议**: 并行开展 Phase 0（清理）和 Phase 1（测试 + CONFIRM），这两个不冲突。Phase 0 花 1 天做完，Phase 1 花 3-5 天。然后评估是否进入 Phase 2。

---

> **文档维护说明**  
> 本文档与 `Agent模块架构设计.md` 配合使用：架构设计记录"现在是什么样"，发展路线记录"未来要去哪"。  
> 每个 Phase 完成后，在"现状评估"章节更新进度条，并链接到新实现的代码路径。
