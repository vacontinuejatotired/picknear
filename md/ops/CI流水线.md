---
status: current
source_of_truth:
  - .github/workflows/ci-cd.yml
  - scripts/ci/notify.sh
superseded_by:
---

# CI 流水线说明

> **最后更新**: 2026-09-17
> **场景**: GitHub Actions 云 runner 全分支编译测试与提交通知 + push master 自动构建镜像推送 ACR

---

## 1. 流水线总览

| Workflow | 文件 | 触发 | 作用 |
|----------|------|------|------|
| CI/CD | `.github/workflows/ci-cd.yml` | push 任意分支（含纯文档）/ PR / 手动 | CI 编译 + 单测 → 代码变更且 CI 通过后才构建镜像推 ACR |

**核心改动（2026-09-17）**：此前 CI 与构建是两条独立 workflow（`ci.yml` + `build-image.yml`），CI 失败不阻断镜像构建。现已合并为 `ci-cd.yml`，用 `needs: ci` 串联——CI job 通过后 build-image job 才执行。

**通知改动（2026-09-17）**：所有分支 push 与手动触发都会发送通知，内容包含提交说明、作者、分支、Commit 和流水线链接；纯文档 push 仍跑 CI 和通知，但通过 `changes` job 跳过镜像构建。

**CD 是有的**（2026-10-05 更正）：VM 上跑了 `picknear-watchtower` 服务，每 30s 轮询 ACR digest，
有变化就自动 `pull` 并重建 `app` / `frontend` 容器（只更新带 `com.centurylinklabs.watchtower.enable=true` label 的容器）。
详见 `vm-docs/picknear-watchtower/`。VM 不常开时，开机后手动 `docker compose pull && up -d --no-build` 补齐。

## 2. Job 结构

```
push/PR/workflow_dispatch
        │
        ▼
    ┌─ changes ─────────────────────────┐
    │ 判断是否包含代码变更               │
    │ 纯文档 push → 跳过镜像构建         │
    └───────────┬───────────────────────┘
                │
                ▼
    ┌─ ci ──────────────────────────────┐
    │ check_docs → setup JDK17 →        │
    │ compile → unit test               │
    └───────────┬───────────────────────┘
                │ (needs: ci)
                ▼
    ┌─ build-image ─────────────────────┐
    │ 仅代码变更 + push master / 手动    │
    │ docker buildx → push ACR          │
    │ tag: latest + sha-xxxxxxx         │
    └───────────┬───────────────────────┘
                │ (always)
                ▼
    ┌─ notify ──────────────────────────┐
    │ 飞书/钉钉通知成功/失败            │
    │ 所有 push / 手动触发              │
    └───────────────────────────────────┘
```

## 3. CI 细节

- JDK 17（temurin）+ Maven 缓存（`actions/setup-java` cache: maven）
- 执行 `mvn test`，**跳过 4 个需要外部环境（MySQL/Redis/RabbitMQ/Langfuse）的 `@SpringBootTest` 集成测试**：

```
!com.hmdp.TokenTest
!com.hmdp.agent.observability.AgentTracerIntegrationTest
!com.hmdp.agent.observability.LangfuseSmokeTest
!com.hmdp.PickNearApplicationTests
```

- 其余测试全部为纯 Mockito 单测，可在云端跑（surefire 已配 `-XX:+EnableDynamicAgentLoading`，JDK 17 无 mock maker 问题）
- 测试代码在 `picknear/src/test/`，**必须保持 `mvn test -DskipITs` 可全绿**——新增/修改主代码时同步更新测试
- **失败时上传 surefire 报告**（2026-10-05）：runner 是一次性的，跑完即销毁，报告不留存就只能本地复现。
  失败后在 Actions → 对应 run → **Artifacts** 里下载 `surefire-reports-<run_id>`
- **并发控制**（2026-10-05）：`concurrency: group: ci-cd-${{ github.ref }}`，同一 ref 上的多次触发排队执行。
  PR 事件允许取消（`cancel-in-progress: ${{ github.event_name == 'pull_request' }}`），
  push / 手动触发排队等待，**不中断镜像构建**——推到一半被打断会留下残缺 digest，
  且两个构建并行时后完成的那个可能是旧 commit，会把它推成 `latest`

> 前端仓库（`frontend`）已于 2026-10-05 对齐同一套结构：`ci.yml` + `build-image.yml` 合并为 `ci-cd.yml`，
> `build-image` job 声明 `needs: build-and-test`。此前两条 workflow 互不相识，类型检查失败照样推镜像，
> watchtower 30s 后直接部署到生产。详见 `nginx-1.18.0heima/frontend/CLAUDE.md`。

## 4. Build Image 细节

**触发条件**：`needs: ci`（CI 通过）且满足以下之一：
- push 到 `master` 分支且包含代码变更（自动触发）
- `workflow_dispatch` 手动触发（可选 tag）

**镜像 tag 策略（2026-09-17 新增）**：
- `picknear-app:latest` — 始终指向最新构建
- `picknear-app:sha-xxxxxxx` — Git commit short SHA 前 7 位，可追溯到具体 commit
- 手动触发时可指定额外 tag。tag 会校验为合法 Docker tag，且与 `latest`、`sha-xxxxxxx` 去重

**构建配置**：
- 构建上下文 `picknear/`（Dockerfile + `docker/maven/settings.xml` 国内镜像源）
- **GHA 层缓存**：`cache-from/cache-to: type=gha,scope=picknear-app`，显式 scope 避免缓存挤占。依赖层是镜像层缓存（非 `--mount=type=cache`），pom.xml 不变则依赖层命中
- **必须关闭 provenance/sbom**：ACR 个人版不支持 OCI attestation 附件，开启会报 `denied: unknown manifest class`
- **不要用 `type=registry` 构建缓存兜底**：ACR 个人版拒绝 buildkit 的 cacheconfig manifest
- ACR 登录凭据来自 GitHub Secrets：`ALIYUN_ACR_USERNAME` / `ALIYUN_ACR_PASSWORD`

**手动触发**：

```bash
# GitHub 仓库 → Actions → CI/CD → Run workflow
# 可选输入 tag（默认 latest）
```

> `workflow_dispatch` 触发前提：workflow 文件必须存在于仓库默认分支（master）。

## 5. 通知

**推荐使用飞书自定义机器人**：它是入站 Webhook，配置成本与钉钉接近，支持文本通知和签名校验。QQ 官方机器人不是简单地替换 Webhook URL，需要 AppID、ClientSecret、群 OpenID 和主动消息权限，当前不作为默认 CI 通知渠道。

### 飞书（推荐）

GitHub 仓库 → Settings → Secrets and variables → Actions：

| 类型 | 名称 | 必填 | 说明 |
|---|---|---|---|
| Secret | `FEISHU_WEBHOOK` | 是 | 飞书群自定义机器人 Webhook URL |
| Secret | `FEISHU_SIGN_SECRET` | 否 | 开启签名校验时填写 |
| Variable/Secret | `NOTIFY_PROVIDER` | 否 | 可显式设置为 `feishu`；优先从 Variables 读取，Secrets 作为兼容回退 |

### 钉钉（兼容保留）

旧配置继续可用：

| 类型 | 名称 | 必填 | 说明 |
|---|---|---|---|
| Secret | `DINGTALK_WEBHOOK` | 是 | 钉钉自定义机器人 Webhook URL |
| Secret | `DINGTALK_SIGN_SECRET` | 否 | 开启加签时填写 |
| Secret | `NOTIFY_WEBHOOK` | 旧名兼容 | 作为 `DINGTALK_WEBHOOK` 的回退值 |
| Variable/Secret | `NOTIFY_PROVIDER` | 否 | 设置为 `dingtalk`；优先从 Variables 读取，Secrets 作为兼容回退 |

### 行为

- 未显式设置 `NOTIFY_PROVIDER` 时，已有 `FEISHU_WEBHOOK` 则走飞书，否则已有 `DINGTALK_WEBHOOK` / `NOTIFY_WEBHOOK` 则走钉钉
- 未配置任何 Webhook 时自动跳过，不影响流水线
- 通知失败会检查平台返回码并让 `notify` job 失败，不再静默成功
- 通知内容：分支、commit SHA、作者、提交说明、构建状态、流水线链接
- 所有分支 push 和手动触发都会推送；PR 事件不重复推送
- feature 分支显示“当前分支不构建镜像”，纯文档 push 显示“仅文档变更，未构建镜像”

## 6. 部署（有机器时）

```bash
# 任意机器上（需 docker + 已 login ACR）：
docker compose pull app && docker compose up -d --no-build

# 指定 SHA 版本（回滚到具体 commit）：
# 编辑 docker-compose.yml 的 image tag 为 sha-xxxxxxx 后 pull
```

## 7. 变更记录

- **2026-10-05**：
  - 加 `concurrency` 并发控制，避免并行构建争抢 `latest` tag
  - CI 失败时上传 `surefire-reports` 产物，便于云端排查
  - 生产密钥参数化：`application-{dev,prod}.yaml` 与 `docker-compose.yml` 里的 MySQL/Redis/RabbitMQ 密码
    改为 `${DB_PASSWORD}` / `${REDIS_PASSWORD}` / `${RABBITMQ_PASSWORD}` 占位符，从 `.env` 注入，配置里不再有明文
  - 接入 Flyway 管理 schema 增量（仅 prod profile，baseline 版本 1），详见 `Docker部署指南.md` §3.5
  - `notify.sh` 飞书签名：一度按标准 HMAC 用法改写，线上即报 sign match fail（code 19021）、
    通知全部失败；已还原为飞书官方算法（拼接串作 HMAC key、消息传空）并在代码内加注释警示。
    飞书与钉钉的签名算法不同，改动前务必对照官方文档
  - 前端 nginx.conf 单一来源：compose 不再用跨仓库 `../../` 路径挂载覆盖，配置随镜像分发
  - 更正"无 CD 自动部署"的描述：VM 上有 watchtower 每 30s 自动拉取部署
- **2026-09-17**：合并 `ci.yml` + `build-image.yml` → `ci-cd.yml`（CI 通过才构建镜像）；镜像新增 `sha-xxxxxxx` tag；通知覆盖所有分支并附带作者/提交说明；纯文档 push 跳过镜像构建；通知 provider 化，推荐飞书并保留钉钉兼容；修复手动 tag 未生效；统一 `actions/checkout@v5`；删除半成品 `vm-docs/deploy-vm.sh`
- **2026-08-30**：首次验证 CI 构建成功；测试代码回归 git 跟踪；删除 `dag/` 废弃包
