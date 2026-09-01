# CI 流水线说明

> **最后更新**: 2026-08-30
> **场景**: GitHub Actions 云 runner 自动编译测试 + push master 自动构建镜像推送 ACR

---

## 1. 流水线总览

| Workflow | 文件 | 触发 | 作用 |
|----------|------|------|------|
| CI | `.github/workflows/ci.yml` | push 任意分支 / PR | 编译 + 单元测试（质量门禁） |
| Build Image | `.github/workflows/build-image.yml` | **push master**（自动）+ 手动（workflow_dispatch） | 构建 Docker 镜像推阿里云 ACR |

无 CD 自动部署：本地无常在线服务器（VM 不常开），master 上构建出新镜像后，任意有 docker 的机器 `docker compose pull && up -d --no-build` 拉取镜像即可。

## 2. CI 细节

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

## 3. Build Image 细节（push master 自动 + 手动触发）

**自动触发**：push 到 `master` 分支即自动构建推送 ACR（`on.push.branches: [master]`，且忽略纯 `md/**`/`*.md` 文档改动）。后端日常开发在 `feature` 分支不触发，**合并到 master 即发布新镜像**；前端直接在 `master` 开发，每次 push 即重新构建。

**手动触发**：需要指定 tag 或想手动重发时：

```bash
# GitHub 仓库 → Actions → Build Image → Run workflow
# 可选输入 tag（默认 latest）
```

- 构建上下文 `picknear/`（Dockerfile + `docker/maven/settings.xml` 国内镜像源）
- 构建后推送 ACR 两个标签：`picknear-app:{tag}` + `picknear-app:latest`（`latest` 始终指向最近一次构建）
- **GHA 层缓存**：`cache-from/cache-to: type=gha,scope=picknear-app`，显式 scope 避免与仓库内其他 job（ci.yml）缓存互相挤占。依赖层是**镜像层缓存**（非 `--mount=type=cache`，GHA runner 不共享 mount），pom.xml 不变则依赖层命中、不重新下载；GHA 缓存被淘汰时该层需重建，属正常
- **必须关闭 provenance/sbom**（`build-image.yml` 已配）：ACR 个人版不支持 OCI attestation 附件，开启会报 `denied: unknown manifest class for application/vnd.oci.empty.v1+json`
- ACR 登录凭据来自 GitHub Secrets：
  - `ALIYUN_ACR_USERNAME`：ACR 登录用户名（即阿里云账号名，见 `vm-docs/deploy-vm.sh` 的 `USER`）
  - `ALIYUN_ACR_PASSWORD`：ACR 访问凭证的固定密码（阿里云控制台 → 容器镜像服务 → 访问凭证）
- **workflow_dispatch 触发前提**：workflow 文件必须存在于仓库默认分支（master），改动后需同步到 master（`git push github <tmp>:master`）
- ✅ 2026-08-30 首次验证成功：`picknear-app:latest` 已推送（ACR 控制台可见）

## 4. 部署（有机器时）

```bash
# 任意机器上（需 docker + 已 login ACR）：
docker compose pull app && docker compose up -d --no-build
```

## 5. 2026-08-30 清理记录

- **测试代码回归 git 跟踪**：此前 `src/test/` 被 gitignore，CI 测试步骤空跑。已恢复跟踪，并修复测试与主代码的脱节：
  - `ExponentialBackoffRetryStrategy` 主代码 import 混搭（`executionPlan.GraphAnalyzer` + `agent.model.ToolMetadata`）导致主代码编译不过——已统一为 executionPlan 套
  - `SseUtilsTest` / `AgentTracerIntegrationTest` 引用 SSE 挪包前的旧路径——已同步
  - `ChatControllerTest` 补 UserHolder 上下文 + 新依赖 mock
  - `AiServiceImplE2ETest` 按主类现状重写（PromptHookExecutor/StreamingChatInvoker/SseResponseProcessor 新结构）
  - `AiServiceImplUnitTest` 测已删除的私有方法，`@Disabled` 保留待重写
- **删除 `dag/` 废弃包（27 文件）**：2026-08-23 迁移到 `executionPlan`/`execution` 后未删的旧版孤岛，7 个 `@Component` 与新套同名会 bean 冲突；`legacy/plan` 是有意保留的回退链，未动
- **测试资源凭据清理**：`src/test/resources/application-dev.yaml` 中废弃服务器凭据已替换为本地占位值
