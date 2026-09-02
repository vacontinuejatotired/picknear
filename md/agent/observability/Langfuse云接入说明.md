# Langfuse 云接入说明（M0/M1 实施指南）

> **版本**: v1.1
> **最后更新**: 2026-09-02
> **上游文档**: [Agent全链路观测架构设计](./Agent全链路观测架构设计.md)（§6.5 接入、§10 里程碑）
> **用途**: 在主机开发环境落地 M0（配额评估）+ M1（链路打通）的逐条操作指引
>
> **⚠️ 定位注记（2026-08-15）**：本文件是**默认观测后端（Langfuse）** 的操作指南。观测后端已支持可插拔（策略 + 装配器 + 能力驱动，见 [观测后端解耦改造方案](./观测后端解耦改造方案.md)）——换后端时本文的链路打通方法不变（仍是 OTLP），只有 endpoint/凭据/专有 header（§4）与配额口径（§1/§7）随后端变化；本文提到的「Langfuse 不展示自定义属性 → span 名编码」是 **Langfuse 特有行为**，切到支持 attributes 的后端时由后端能力开关自动关闭编码、语义改由 attributes 展示。
>
> **v1.1 修订（2026-09-02，评测链路打通）**：§5.6「自定义属性全部不展示」结论新增**可转译例外**——`langfuse.observation.input/output` 前缀（LLM content 补发，2026-09-01 起）会被 Langfuse 转译为 observation **主字段** input/output，评测（LLM-as-a-judge 取数）依赖此机制。

---

## 1. 账号与区域（已完成 ✅）

- Langfuse Cloud **免费档（Hobby）**，区域 **JP**，控制台：`https://jp.cloud.langfuse.com`
- 免费档官方限制（2026-08 核对，以官方页为准）：

| 限制项 | 数值 | 影响 |
|--------|------|------|
| 配额 | 50,000 units/月（1 unit = 1 trace/observation/score） | 约 20 units/请求 → 全量 ≈2500 请求/月 |
| 保留 | 30 天（自动清理，不可自定义） | 无需自建 TTL |
| 用户 | 2 个（硬限制） | 天然限次（D8）：管理员 + 演示访客 |
| 摄取限流 | 1000 req/min（OTLP/ingestion） | 演示量级不可达 |
| 删除 API | 50 req/day | 合规兜底 |

## 2. 凭据配置（已完成 ✅）

写入 `picknear/picknear/.env`（已被 .gitignore 忽略，密钥不进仓库）：

```bash
# ---- Langfuse 云（AI 观测，Hobby 免费档）----
LANGFUSE_PUBLIC_KEY="pk-lf-xxxx"          # 控制台 → Project → API Keys
LANGFUSE_SECRET_KEY="sk-lf-xxxx"
LANGFUSE_BASE_URL="https://jp.cloud.langfuse.com"
```

## 3. 依赖（已加入 pom.xml ✅，主机无需再改）

```xml
<!-- AI 全链路观测：Micrometer Observation → OTel → OTLP → Langfuse 云 -->
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-tracing-bridge-otel</artifactId>
</dependency>
<dependency>
    <groupId>io.opentelemetry</groupId>
    <artifactId>opentelemetry-exporter-otlp</artifactId>
</dependency>
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-registry-prometheus</artifactId>
</dependency>
```

⚠️ **全部不写版本**，走 Boot 3.4.4 BOM（micrometer 1.14.5 / micrometer-tracing 1.4.4 / opentelemetry 1.43.0，与现有 otel-api 一致）。手工钉版本会与 bridge 产生 API 漂移。

## 4. 接入配置（application.yaml / application-prod.yaml）

```yaml
management:
  otlp:
    tracing:
      endpoint: ${LANGFUSE_BASE_URL}/api/public/otel/v1/traces   # ⚠️ 实测必须带 /v1/traces！
      headers:
        Authorization: Basic ${LANGFUSE_BASIC_AUTH}
        x-langfuse-ingestion-version: "4"   # 实时摄取（v3 批量约 5s 延迟）
  tracing:
    sampling:
      probability: 1.0        # 验证期全量；日常 0.1
```

- **⚠️ endpoint 实测修正（2026-08-03）**：`.../api/public/otel` 返回 404——OTel SDK 1.43 不自动追加 signal path，**必须写全 `/api/public/otel/v1/traces`**（curl 验证：该路径 POST 返回 400/200，不带则 404）。架构设计文档 §6.5/§7 的配置示例已同步修正
- `${LANGFUSE_BASIC_AUTH}` = `echo -n "pk-lf-xxx:sk-lf-xxx" | base64` 的结果（加进 .env）
- **实时摄取**：OTLP 请求头加 `x-langfuse-ingestion-version: 4`（v3 默认批量摄取约 5s 延迟，不加头"看不到 span"先查这里，别误判为埋点 bug）
- service name = `spring.application.name`（当前 `hmdp`），Langfuse 里按此区分
- 版本门槛自动满足：云版永远最新（OTLP 端点需 ≥3.22 / OTel 路线需 ≥3.63）
- 只收 trace；日志/metric 的 404 导出日志可忽略

## 5. 冒烟测试（M1 验证，文件已就位 ✅）

**位置**：`picknear/src/test/java/com/hmdp/agent/observability/LangfuseSmokeTest.java`

**做什么**：启动完整 Spring 上下文 → 调用一次纯文本 LLM（无工具）→ Spring AI 自动产生两层 span（`spring.ai.chat.client` + `gen_ai.client.operation`，含 token usage）→ 经 OTLP 导出到 Langfuse 云 → 等待 20s 导出窗口。

**运行前提（主机环境）**：
1. JDK 17 + Maven 3.6.3+
2. 环境变量：`set -a; source .env; set +a`（需要 `DASHSCOPE_API_KEY` / `LANGFUSE_PUBLIC_KEY` / `LANGFUSE_SECRET_KEY` / `LANGFUSE_BASE_URL` / `DB_PASSWORD`）
   - ⚠️ `.env` 的 `DASHSCOPE_API_KEY` 曾是占位符 `your-dashscope-api-key`，已从 10 主机的 app 容器环境补真实值
3. **MySQL/Redis 地址**：测试内已用 `@DynamicPropertySource` 覆盖为容器宿主端口（10 主机 192.168.49.10 的 43307/46379）；**主机需先建 SSH 隧道**：
   ```bash
   ssh -i ~/.ssh/docker-virtual-machine -f -N -L 43307:127.0.0.1:43307 -L 46379:127.0.0.1:46379 docker@192.168.49.10
   ```
   （ssh config 已补 `192.168.49.10` 条目：User docker + IdentityFile docker-virtual-machine）

**⚠️ 测试类必须标 `@AutoConfigureObservability`（实测坑，2026-08-03）**：Boot 3.4 的测试机制（DisableObservabilityContextCustomizer）默认把 `management.tracing.enabled` 置为 false → `OtlpTracingConfigurations$Exporters` 不匹配 → **spanExporter bean 不创建，span 全部静默丢弃**。症状：LLM 调用成功但 Langfuse 无任何 trace，且日志无导出错误。排查顺序：① 上下文里有没有 `otlpHttpSpanExporter` bean ② `management.tracing.enabled` 是否为 false。

**运行**：
```bash
mvn test -Dtest=LangfuseSmokeTest
```

**预期结果**：Langfuse 网页 → Projects → Traces，出现一条新 trace（service=hmdp），结构：
```
agent 会话链路（后续 M2 才有业务 span）
└── spring.ai.chat.client            ← 第 1 层（ChatClient 层）
    └── gen_ai.client.operation      ← 第 2 层（ChatModel 层，含 token usage）
```
⚠️ 每调用两层是**正常结构**，不是重复调用。

**实测呈现（2026-08-03 已跑通）**：Langfuse 云对 Spring AI 的 OTel span 做了转译折叠——UI/API 里显示为 1 个 `GENERATION chat qwen-plus` + 1 个 `SPAN http post`（http 是 model 层的 DashScope 调用），不再区分 client/model 两层。**这是 Langfuse 的正常转译，不是丢 span**。
**已知待办（M1 调用点改造时验证）**：当前 GENERATION 的 metadata/usage 为空——`DefaultChatModelObservationConvention` 无条件写 usage（只要 `ChatResponse` metadata 有 usage），怀疑 DashScope 适配器响应未填 usage 或 model 层 observation 被合并；5 处调用点改取 `ChatResponse` 后在此验证。

**✅ 实测状态（2026-08-03）**：冒烟测试已跑通，Langfuse 云可见 LLM trace。

## 5.5 观测白名单（M1.5，已落地 ✅）

**背景**：Boot 3.4 的 task observation 会把 `@Scheduled`/`@Async` 任务（BatchLoadCache 每 2s、CacheMonitor 每 30s）全量上云，约 **21.6 万 units/月，超 Hobby 50k 配额 4 倍**。

**实现**：`ObservabilityTraceFilter`（`io.micrometer.observation.ObservationPredicate`）全局过滤，只放行前缀 `agent.` / `spring.ai.` / `gen_ai.`，其余 observation 不创建、零导出零配额。Boot 3.4 自动收集所有 ObservationPredicate bean，无需手动注册。扩展：`hmdp.ai-observability.trace-filter.include-prefixes` 追加前缀（追加非覆盖）。

**实测效果**：窗口内 trace 从 ~90 条（load-cache 每 2s）降到 **1 条**（仅 LLM 调用）。

> 实现说明：micrometer 1.14 的 `ObservationFilter` 是 `map(Context)` 语义（null 丢弃行为未确认），故用语义明确的 `ObservationPredicate`（false = 不创建），Boot 自身配置过滤也用此机制。

## 5.6 自定义属性可见性（M1.5 实测，⚠️ 影响 M2 设计）

**实测结论（2026-08-03，Langfuse 4.2.0 JP 云版）**：**OTLP 路径下自定义 span attributes 全部不展示**——普通前缀与 `langfuse.observation.metadata.*` 前缀均未转译进 metadata（官方文档称应落 `metadata.attributes` catch-all，JP 实例行为不一致；ingestion API 直发 201 成功但查询未回显，未闭环）。

> **例外注记（2026-09-02 实测）**：上述"全部不展示"存在**一个可转译例外**——`langfuse.observation.input` / `langfuse.observation.output`（Langfuse SDK 协议前缀）会被 OTLP 提取器转译为 observation **主字段** input/output（提取瀑布 Step 1），不再留在 metadata。项目自 2026-09-01 起用该 key 补发 LLM content（`ChatModelObservationConventionConfig`），使 generation 主字段有值——**LLM-as-a-judge 评测取数的前提**。其余自定义属性（`agent.*` / `guard.*` 等）仍按本结论"不展示/仅落 metadata.attributes"处理。

**对 M2 的影响**：业务语义改由 **span 名编码**（span 名在 Langfuse 100% 可见）：
- 命名规则：`agent.{类型}.{关键语义}`，如 `agent.tool_call.queryShop`、`agent.guard.BLOCK.deleteBlog`
- guard 工具调用 span（2026-08-11 增强）：语义 = `{决策}.{工具}.{模型}.{参数摘要}`，如 `ALLOW.query-weather.qwen-plus-2025-07-28.{city:北京}`——模型名 + 脱敏参数摘要编码进 span 名，控制台免点击直读执行内容
- LLM generation 名（2026-08-12 增强，`ChatModelObservationConventionConfig`）：`{调用方}-{任务}-chat {模型}`，如 `subagent-exec-query-weather,query-user-blogs-chat qwen-plus-2025-07-28`（subagent-exec 携带剩余任务工具名清单）、`subagent-compress-query-weather-chat qwen-plus-2025-07-28`（compress 携带被压缩的工具名）——任务标识编进名，控制台直读"这轮在驱动哪个任务"
- 属性仍**全量写入** OTel span（标准数据不丢）：M2.5 InMemorySpanExporter 断言用 + 未来 Langfuse 修复/自建面板即得
- 类型统计用前缀匹配（`agent.tool_call.*`），不受语义后缀影响

**排查清单**（看不到 span 时按序查）：
1. 等 5–20s（批量摄取延迟）
2. 控制台日志搜 `Failed to export` / OTLP 导出异常
3. OTLP 端点是否含 `/api/public/otel` 路径
4. `Authorization` 头 base64 是否正确（pk:sk，冒号分隔）
5. 网络可达 `jp.cloud.langfuse.com:443`（curl 验证）
6. `management.tracing.sampling.probability` 是否为 1.0

## 6. 演示开关（D8，公网演示前执行）

- 免费档 **2 用户硬限制** = 天然限次：管理员（自己）+ 演示访客各占一个名额
- 访客账号配 **Viewer（只读）角色**——可看 trace，不可改配置/删数据
- **演示流程**：演示日开放访客账号 → 演示结束立即停用/改密
- 若 Hobby 不支持 Viewer 角色：访客账号演示后直接停用

## 7. 配额预算与监控

| 场景 | 采样率 | 月请求上限 |
|------|--------|-----------|
| 日常开发 | 0.1–0.2 | ≈25000 |
| 压测/演示日 | 1.0 | ≈2500 |

- 超限表现（收费 or 限用）注册后实测确认
- 30 天保留自动清理，无需操作

## 8. 后续里程碑预告

- **M2**：AgentTracer 业务埋点（9–11 处）+ 跨线程传播（架构文档 §6.1/§6.2，API 以 1.14.5 实测为准：`openScope()` / 三参 `createNotStarted`）
- **M2.5**：InMemorySpanExporter 集成测试（架构文档 §10）
- **M3**：AgentMetrics + `/actuator/prometheus`
- **M4**：Guard 投票平铺、plan.tools[]、脱敏验证
