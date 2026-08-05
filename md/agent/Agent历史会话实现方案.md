# Agent 历史会话实现方案

> **版本**: v2.0
> **创建日期**: 2026-08-05
> **状态**: 已审查修订（PLANNING 记最终合并答案；updated_at 显式刷新；SSE 按决策分支落库）
> **相关文档**: [Agent模块架构设计](./Agent模块架构设计.md), [Agent模块发展路线图](./Agent模块发展路线图.md), [SSE后端实现规范](./SSE后端实现规范.md)

---

## 1. 背景与目标

当前 AI 聊天（前端 `/ai` → `AiChat.vue`）没有会话概念：

- `conversationId` 只存在 `api/agent.ts` 的模块级变量 `savedConversationId` 里，**刷新即丢**；
- 后端 `/agent/string/send` 把 `conversationId` 用作 Spring AI `ChatMemory` 的 key（多轮上下文）。
  注意：多轮记忆经 `JdbcChatMemoryRepository` **已落 Spring AI 私有表**（`AgentConfig.java` 的
  `chat_memory` 系列表），但**无 user_id、schema 私有、不可按业务查询**。本方案诉求是
  **用户维度、可查询的业务历史**，与 Spring AI 私有记忆表分离，无冲突。

**目标**：实现「历史会话」——点进聊天框能看到与 AI 的历史消息。

### 已确认的决策

1. **会话形态**：会话列表 + 点进查看（列表列出所有历史会话，点击进入某会话看消息；支持「新对话」）。
2. **表结构**：会话表 `agent_conversation` + 消息表 `agent_message` 两张独立表。
3. **PLANNING 回合**：落库**最终合并答案**（`TaskPlanner` 完成点），**中间规划过程不落库**。

### 范围边界

- **仅记录 AI 回复 + 用户消息**（role = user / assistant 纯文本）。PLANNING 回合的 assistant =
  最终合并答案；tool_call / thought / 子任务 / 阶段进度等中间过程本期不落库。
- 该消息表与未来可能的"用户-用户聊天"消息表**分离**（独立表，不复用通用消息表）。
- 现有聊天主链路（双模 JSON/SSE、Hook 链、两阶段规划、观测埋点）**不改行为**，只在收尾处追加持久化。
- ⚠️ **既有问题（不在本方案）**：SSE 模式直连 `dashScopeChatModel.stream()`，绕过带
  `MessageChatMemoryAdvisor` 的 ChatClient，SSE 回合**不写入 ChatMemory**（JSON 模式会写）。
  历史功能会暴露它：打开历史会话续聊，AI 对之前的 SSE 回合无记忆。后续可用 `agent_message`
  回灌 ChatMemory 一并解决，本期不改。

---

## 2. 后端设计

### 2.1 建表（DDL）

新增两张表，追加到 `picknear/src/main/resources/db/heima-init.sql`（供全新部署初始化）。

```sql
CREATE TABLE `agent_conversation` (
  `id`              bigint unsigned NOT NULL AUTO_INCREMENT,
  `conversation_id` varchar(64)  NOT NULL COMMENT '全局唯一会话ID（UUID）',
  `user_id`         bigint unsigned NOT NULL COMMENT '所属用户ID',
  `title`           varchar(200) NOT NULL DEFAULT '' COMMENT '会话标题（首条用户消息截断）',
  `status`          tinyint      NOT NULL DEFAULT 0 COMMENT '0-活跃 1-归档 2-删除',
  `created_at`      datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`      datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_conversation_id` (`conversation_id`),
  KEY `idx_user_id` (`user_id`)
) COMMENT 'AI 对话会话元数据';

CREATE TABLE `agent_message` (
  `id`              bigint unsigned NOT NULL AUTO_INCREMENT,
  `conversation_id` varchar(64)  NOT NULL COMMENT '所属会话ID',
  `user_id`         bigint unsigned NOT NULL COMMENT '所属用户ID',
  `role`            varchar(16)  NOT NULL COMMENT 'user / assistant（本期只存这两种）',
  `content`         text         NOT NULL COMMENT '消息内容',
  `created_at`      datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_conversation_id` (`conversation_id`),
  KEY `idx_user_id` (`user_id`)
) COMMENT 'AI 对话消息明细';
```

> **⚠️ 存量开发库补表**：compose 的 MySQL 卷已初始化过，`docker-entrypoint-initdb.d` 不会重跑。实施时需对运行中的库手动执行同样的 DDL：
> ```bash
> docker exec -i picknear-mysql mysql -uroot -p"$DB_PASSWORD" heima < <临时 DDL 文件>
> ```
> 开发库密码在 `picknear/picknear/.env` 的 `DB_PASSWORD`。

### 2.2 实体 + Mapper（MyBatis-Plus，仿 `entity/Blog.java` 模式）

| 文件 | 说明 |
|------|------|
| `agent/entity/AgentConversation.java` | `@TableName("agent_conversation")`，字段映射上表 |
| `agent/entity/AgentMessage.java` | `@TableName("agent_message")` |
| `agent/mapper/AgentConversationMapper.java` | `extends BaseMapper<AgentConversation>` + 会话列表聚合 `@Select` |
| `agent/mapper/AgentMessageMapper.java` | `extends BaseMapper<AgentMessage>` |

> **⚠️ @MapperScan 需扩展**：`PickNearApplication` 的 `@MapperScan("com.hmdp.mapper")` 扫不到
> `agent/mapper`，须改为 `@MapperScan({"com.hmdp.mapper", "com.hmdp.agent.mapper"})`，否则 Mapper Bean 缺失。

### 2.3 历史服务（新）

`agent/service/AgentHistoryService.java` + `impl/AgentHistoryServiceImpl.java`，职责：

- `recordTurn(Long userId, String conversationId, String userContent, String assistantContent)`（`@Transactional`）
  - 按 `(conversation_id, user_id)` 查询归属：不存在 → 建会话（`title` = `userContent` 截断 100 字）；
    存在 → 仅续传，并**显式 `UPDATE ... SET updated_at = NOW()`**（⚠️ `ON UPDATE CURRENT_TIMESTAMP`
    只在 UPDATE 行时触发，仅 INSERT 消息不会刷新活跃时间 → 会话列表排序失效）；
  - 写 user 消息 + assistant 消息两条（`created_at` 由 DB 默认填充）。
  - **只在一次成功的回合结束时调用**（AI 失败/被 BLOCK 时不落库，避免孤儿消息）。
  - **调用方必须 try/catch 包裹（最佳努力）**：SSE 模式下抛异常会被重试循环捕获 → 重跑 LLM 浪费
    token；JSON 模式下会中断响应。持久化失败只记日志，不影响聊天主链路。
- `listConversations(Long userId)` → 按 `updated_at DESC` 返回当前用户会话（`conversation_id`、`title`、`updated_at`、`message_count`），供列表页。
- `listMessages(Long userId, String conversationId)` → **先校验会话归属**（按 `(conversation_id, user_id)`，
  不属于当前用户返回空列表，不泄露存在性），再按 `created_at ASC` 返回消息（role + content + created_at）。

> 会话列表的 `message_count` 用一条 `LEFT JOIN + GROUP BY` 聚合查询（`AgentConversationMapper.selectConversationList`），避免 N+1；GROUP BY 含全部非聚合列，兼容 `ONLY_FULL_GROUP_BY`。

### 2.4 接入发送链路（改 `AiServiceImpl` + `ChatContext` + `TaskPlanner`）

不动既有编排逻辑，只在"回合成功收尾"点调用 `historyService.recordTurn(...)`（统一走 best-effort try/catch）。

**SSE 模式**（`doChatWithToolcall`）——**按 AfterAiHook 决策分支**（`AiResponseRouter` 有 BLOCK/REPLACE/PLANNING/PASS 四态，且 `TaskTriggerHook` 触发词很宽，PLANNING 是高频路径，必须显式区分）：

| 决策 | 落库行为 | 挂点 |
|------|---------|------|
| **PASS** | `recordTurn(userId, conversationId, content, fullResponse)` | `[Phase1] AI 流式回复完成` 处，`responseRouter.route` 之后 |
| **REPLACE** | `recordTurn(userId, conversationId, content, afterResult.getReplacedText())` | 同上（用户看到的是替换文本，不能存原始 fullResponse） |
| **BLOCK** | **不落库**（用户看到的是阻断原因，非成功回合） | — |
| **PLANNING** | `recordTurn(userId, conversationId, 原始content, result)` | **不在 Phase1 记录**（此时只有开场/规划草稿）；在 `TaskPlanner.planAndExecuteAsync` 完成点（`planAndExecute` 返回最终合并 `result` 后）记录 |

- `ChatContext` 新增 `originalContent` 字段（用户原始输入，Hook 替换前），SSE 构建 ctx 时写入；
  TaskPlanner 完成点经 `ctx.getUserId()/getConversationId()/getOriginalContent()` 取数。
- **PLANNING 完成点 ctx == null（快照恢复路径）时跳过**：该路径无 userId 且会再次完成，防止重复落库
  （当前 `hasConfirmTool` 恒 false，此路径为死代码，注释说明即可）。
- `userId` 取自 `UserHolder.getUserId()`（**主线程捕获**，异步线程 ThreadLocal 已丢，现有代码就是这么拿的）；
  `conversationId` 已由 Controller 生成/传入。

**JSON 模式** `chatReturnStringResult`：拿到 `result`（LLM 完整回复）后，
`recordTurn(userId, conversationId, content, result)`（PromptHook BLOCK 已提前 return，天然跳过）。

### 2.5 历史查询接口（新 Controller）

`agent/controller/HistoryController.java`，`@RequestMapping("/agent")`（`/agent/**` 已在 `MvcConfig` 登录拦截器保护下，无需新配置）：

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/agent/conversations` | 当前用户会话列表 → `Result.ok(List<ConversationVO>)` |
| GET | `/agent/conversations/{conversationId}/messages` | 某会话消息列表（校验归属）→ `Result.ok(List<MessageVO>)` |

返回用轻量 VO（`agent/dto/` 下）：`ConversationVO{conversationId, title, updatedAt, messageCount}`、`MessageVO{role, content, createdAt}`，不直接吐 Entity。

---

## 3. 前端设计

### 3.1 `api/agent.ts`

- 修类型：`chat` 的响应数据实际是 `{ content, conversationId }`（后端 JSON 模式返回 Map），当前 `ApiResponse<string>` 是**潜在 bug**（SSE 失败降级时 `placeholder.content` 会被赋成对象）。改为 `ApiResponse<{ content: string; conversationId: string }>`。
- `chatStream` 签名改为显式传 `conversationId?: string`（替换模块级 `savedConversationId`），并新增 `onConversationId` 回调把后端返回的会话 ID 交还给组件。
- 新增：
  - `listConversations: () => request.get<ApiResponse<ConversationVO[]>>('/agent/conversations')`
  - `getMessages: (conversationId) => request.get<ApiResponse<MessageVO[]>>('/agent/conversations/' + conversationId + '/messages')`
- 在 `types/api.ts`（或 agent.ts 内）加 `ConversationVO`、`MessageVO` 接口。

### 3.2 `views/AiChat.vue`（会话列表 + 聊天双态）

用 `route.query.conversationId` 派生状态 + 本地 `activeConversationId`/`isNew`：

- **状态**：`isChat = isNew || !!activeConversationId`。
  `activeConversationId` 在 onMounted（刷新/深链）、openConversation（点列表项）、handleConversationId（首轮成功）设置；
  在 goToList 清空。用它在"首轮发送固化 URL"时**抑制 watcher 重载历史**（否则会用未落库的空历史清掉正在流式展示的消息）。
- **列表态**（非聊天态）：
  - `PageHeader` title="AI 助手"，`show-back=false`，右侧 slot 放「新对话」按钮。
  - 主体渲染 `components/agent/ConversationList.vue`（新组件）：`onMounted` 调 `listConversations`；空态用现有 `EmptyState`；
    每条显示 title + 相对时间（复用 `utils/format.ts`）+ messageCount；点击 → `openConversation(id)`。
- **聊天态**：
  - 复用现有聊天逻辑（消息流 / 输入区 / SSE），`conversationId` 显式传给 `chatStream`（不再依赖模块级变量）。
  - **新对话**：`isNew=true` → 欢迎语、无历史（欢迎语只在此出现，去掉原 onMounted 无条件塞欢迎语）。
  - **首轮发送成功**：`onConversationId` → `handleConversationId`：设 `activeConversationId`、`isNew=false`、
    `router.replace({ query: { conversationId } })`，刷新/返回都可恢复。
  - **返回列表**：`PageHeader` 新增 `backTo?: string` prop（`handleBack` 设了 backTo 就 `router.replace(backTo)`，
    不再无条件 `router.back()`）+ 组件绑 `@back="goToList"`（重置 isNew/activeConversationId/messages）。
    ⚠️ **顺带修复既有双返回 bug**：原 `AiChat` 绑 `@back="router.back()"` 而 `PageHeader.handleBack` 自己又
    `router.back()` → 点一次返回触发两次跳转。改造后 AiChat 不再绑 `@back="router.back()"`，统一走 backTo + goToList。
    `PageHeader` 改动向后兼容，不影响其他页面（全仓只有 AiChat 绑了 `@back` 且指向 `router.back()`）。

### 3.3 常量文案

`constants/messages.ts` 增加 `newChat: '新对话'`、`historyEmpty`、`chatBackToList` 等文案（不写死中文字符串）。

---

## 4. 验证步骤

1. **编译/启动**：后端 `docker compose -f picknear/picknear/docker-compose.yml up -d --build app`；前端 `npm run dev`。
2. **建表核对**：`docker exec picknear-mysql mysql -uroot -p… heima -e "SHOW TABLES LIKE 'agent%';"` 两张表存在。
3. **功能走查**（浏览器 http://localhost:3000，FootBar「消息」进入 /ai）：
   - 列表态：空态提示 → 点「新对话」进入聊天。
   - 发普通消息（如"推荐一家川菜馆"）→ URL 出现 `?conversationId=xxx`；**刷新** → 自动加载该会话历史（user + assistant 完整文本）。
   - 发触发规划的消息（如"统计一下店铺数量"）→ 规划执行正常；**刷新后历史里这条 assistant = 最终合并答案**（不是 Phase1 开场草稿），且不含 tool_call / 子任务中间过程。
   - 返回列表 → 该会话出现在列表（title=首条用户消息、有时间），点击可再次进入。
   - 在某个会话再发一条 → 返回列表 → **该会话应置顶**（验证 `updated_at` 显式刷新生效）。
   - 建第二个会话 → 列表出现两条，互不串扰。
   - **数据落库检查**：`SELECT * FROM agent_message;` 每回合 user+assistant 两条、role/content 正确；`agent_conversation` title 正确、`updated_at` 随回合更新。
4. **权限**：新接口未登录访问应 401（走登录拦截器）；`getMessages` 他人会话返回空列表。

---

## 5. 改动文件清单

**后端**
- `db/heima-init.sql`（+2 表 DDL）
- `agent/entity/AgentConversation.java`、`agent/entity/AgentMessage.java`（新）
- `agent/mapper/AgentConversationMapper.java`、`agent/mapper/AgentMessageMapper.java`（新）
- `agent/service/AgentHistoryService.java` + `impl/AgentHistoryServiceImpl.java`（新）
- `agent/dto/ConversationVO.java`、`agent/dto/MessageVO.java`（新）
- `agent/controller/HistoryController.java`（新）
- `agent/service/impl/AiServiceImpl.java`（JSON 收尾 + SSE PASS/REPLACE 分支落库，`recordTurnBestEffort`）
- `agent/task/TaskPlanner.java`（PLANNING 完成点落最终合并答案）
- `agent/hook/ChatContext.java`（+`originalContent`）
- `PickNearApplication.java`（@MapperScan 扩展 `agent.mapper`）

**前端**
- `api/agent.ts`（修 chat 类型、chatStream 签名、+listConversations/getMessages）
- `types/api.ts`（+ConversationVO/MessageVO）
- `views/AiChat.vue`（列表/聊天双态重构）
- `components/agent/ConversationList.vue`（新）
- `components/common/PageHeader.vue`（+`backTo` prop，向后兼容）
- `constants/messages.ts`（+新对话等文案）

---

## 6. 实现注意点（踩坑清单）

1. **存量开发库要手动补表**：`heima-init.sql` 只对全新部署生效，运行中的库需手动执行 DDL（见 §2.1）。
2. **前端 `agent.ts` 现成 bug（已修）**：`chat` 类型 `ApiResponse<string>` 与后端实际返回 `{content, conversationId}` 不符，SSE 降级路径会把对象赋给 `placeholder.content`。
3. **SSE 异步线程丢 ThreadLocal**：`recordTurn` 在 `runAsync` 块内调用时，用主线程捕获的 `userId` 局部变量，不能直接 `UserHolder.getUserId()`；PLANNING 完成点从 `ctx` 取。
4. **PLANNING 必须在完成点、且 ctx != null 时落库**：Phase1 的 `fullResponse` 只是开场草稿；快照恢复路径（ctx=null，当前为死代码）会再次完成，跳过防止重复记录。
5. **`recordTurn` 必须 best-effort**：SSE 重试循环会捕获异常重跑 LLM，JSON 模式异常中断响应——调用方 try/catch，失败只记日志。
6. **`updated_at` 需显式刷新**：`ON UPDATE CURRENT_TIMESTAMP` 只在 UPDATE 行时触发，仅 INSERT 消息不会刷新活跃时间。
7. **@MapperScan 需扩展**：新增 `agent/mapper` 不在原扫描范围，改 `@MapperScan({"com.hmdp.mapper", "com.hmdp.agent.mapper"})`。
8. **`PageHeader` 双返回（已修）**：原 AiChat 绑 `@back="router.back()"` + handleBack 自带 `router.back()` = 双跳；改造后走 `backTo` + `goToList`，不再绑 `@back="router.back()"`。
9. **消息只在成功回合落库**：AI 失败 / Hook BLOCK 时不写库；会话标题 = 首条用户消息截断（100 字）。
10. **新接口权限**：挂在 `/agent/**` 下自动走登录拦截器；`getMessages` 校验会话归属（按 conversation_id + user_id）。
11. **SSE 回合不进 ChatMemory（既有问题，不在本方案）**：见 §1 范围边界。
