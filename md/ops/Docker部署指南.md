# PickNear Docker 部署指南

> 前后端分离（Vue + Spring Boot），通过 Docker 镜像 + docker compose 部署到 Linux 虚拟机。
>
> **架构**：nginx（前端静态 + 反向代理）→ Spring Boot 后端 → MySQL / Redis / RabbitMQ
>
> **镜像来源（2026-08-30 起）**：镜像一律由 **GitHub Actions CI/CD 构建并推送阿里云 ACR**，虚拟机（或任意有 docker 的机器）**只 `docker compose pull`，不再本地构建镜像**。

> ## ⚠️ 部署模式说明（2026-08-30 更新）
>
> - **开发**：一律在共享文件夹 `/mnt/hgfs/heima`（主机侧 `E:\heima`）进行，`~/.bashrc` 的 `COMPOSE_FILE` 指向共享文件夹的 `/mnt/hgfs/heima/picknear/picknear/docker-compose.yml`
> - **构建镜像**：**不要在本地 `docker build` / `docker compose build`**。改代码 → push 到 GitHub → 合并到 `master` → `picknear/.github/workflows/build-image.yml` 自动（或手动）构建推送 ACR → 任意机器 `docker compose pull app && docker compose up -d --no-build` 部署
> - `/opt/picknear` 是旧部署副本（2026-08-01 创建），静态拷贝、不随主机更新，**不再用于开发/构建**；按本指南部署新机器时不复制它
> - 踩坑史：2026-08-03 曾因 `COMPOSE_FILE` 指向 `/opt/picknear`，compose 一直构建旧代码（镜像缺失 observability 模块），已改回共享文件夹

---

## 0. 部署拓扑

```
                          ┌─────────────────────────────────────────────┐
   用户 ──48080──>  picknear-frontend (nginx)                           │
                    │  /api → 反向代理 48082                            │
                    │                                                   │
                    └──>  picknear-app (Spring Boot, JVM ≤1G)          │
                          │        │         │                          │
                      picknear-mysql  picknear-redis  picknear-rabbitmq │
                    (43307:3306)   (46379:6379)  (45672/55672)          │
                          └─────────────────────────────────────────────┘
                                   全部容器在 picknear-net 网络内
```

| 服务 | 镜像 | 容器内端口 | 宿主机端口（非常用，防爆破） | 内存上限 |
|---|---|---|---|---|
| mysql | `mysql:8.0` | 3306 | **43307** | 1g |
| redis | `redis:7-alpine` | 6379 | **46379** | 256m |
| rabbitmq | `rabbitmq:4-management-alpine` | 5672 / 15672 | **45672** / **55672** | 512m |
| app | `crpi-...picknear/picknear-app:latest`（ACR） | 8082 | **48082** | 1g |
| frontend | `crpi-...picknear/picknear-frontend:latest`（ACR） | 80 | **48080** | 128m |

> ⚠️ 端口全部改为非常用值（原端口 + 40000），仅宿主机暴露侧改，容器内服务间仍走内部端口直连，互不影响。

---

## 1. 构建镜像（CI/CD，不要本地构建）

镜像由**前后端各自仓库**的 `build-image.yml` 工作流构建，推到阿里云 ACR：

| 仓库 | 工作流 | 触发 | 镜像 |
|---|---|---|---|
| `picknear/.github/workflows/build-image.yml` | Build Image | **push master**（自动）+ 手动 `workflow_dispatch`（可填 tag） | `picknear/picknear-app:{tag}` + `:latest` |
| `frontend/.github/workflows/build-image.yml` | Build Image | **push master**（自动）+ 手动 | `picknear/picknear-frontend:{tag}` + `:latest` |

要点：
- **自动触发**：push 到 `master` 即构建。后端在 `feature` 上开发不触发，**合并到 `master` 的那一刻 = 发布新镜像**（`picknear/CLAUDE.md` 有分支约定）
- **手动触发**：GitHub 仓库 → Actions → Build Image → Run workflow，可选 tag
- 构建用 `docker/build-push-action`，**GHA 层缓存**（`type=gha,scope=picknear-app/-frontend`），依赖层复用：后端 pom.xml / 前端 package-lock.json 不变则依赖层命中，不重新下载
- **必须关闭 provenance/sbom**（已配）：ACR 个人版不支持 OCI attestation 附件，开启会报 `unknown manifest class for application/vnd.oci.empty.v1+json`
- **不要用 `type=registry` 构建缓存兜底**（2026-09-02 实测）：ACR 个人版拒绝 buildkit 的 cacheconfig manifest，`cache-to: type=registry` 会报 `unknown manifest class for application/vnd.buildkit.cacheconfig.v0` 导致**整个构建失败**；`type=gha` 是唯一可行的层缓存来源
- ACR 登录凭据来自 GitHub Secrets：`ALIYUN_ACR_USERNAME` / `ALIYUN_ACR_PASSWORD`
- 构建上下文：后端 `picknear/`（Dockerfile + `docker/maven/settings.xml` 国内镜像源）；前端 `.`（Dockerfile + nginx.conf）
- 镜像 tag 规则：手动触发可指定 `{tag}`，同时总是更新 `latest`。**`latest` 始终指向最新一次构建**

### 构建速度说明（优化已落地）

- 依赖下载走国内镜像源（后端 Maven：华为云→腾讯云→阿里云；前端 npm：npmmirror）
- 依赖层用**镜像层缓存**（非 `--mount=type=cache`）：GHA runner 每次全新，mount cache 不共享；镜像层 + GHA 缓存才能跨构建复用
- GHA 缓存加了显式 `scope`，避免与仓库内其他 job（ci.yml）的缓存互相挤占淘汰
- 后端 multi-stage + Spring Boot layertools 分层，依赖/应用层独立 COPY —— VM pull 时只拉差异层
- 若某次构建"重新全量下载依赖"（依赖文件变化或 GHA 缓存被淘汰），属正常，不必惊慌

**2026-09-02 追加的构建缓存优化**：
- 后端 `pom.xml` **注释了 `spring-milestones` 仓库声明**：项目只用 release（中央仓都有），该声明会导致 go-offline 与 package 版本解析不一致时每次重下 spring-milestones-cn 的依赖（约 52 个）；注释后 spring-milestones 不再参与解析，依赖全部走华为云 central。**改 pom 后首次构建会重建依赖层（全量下载 ~6min，属预期），之后稳定**
- 后端 `package` 加 `-T 1C` 插件级并行；前端镜像构建改用 `npm run build:image`（只 `vite build`，跳过 vue-tsc，类型检查由 `ci.yml` 的 `npm run build` 兜底），`npm ci` 加 `--no-audit --no-fund`
- 实测缓存命中时：后端构建 ~7s（全层 CACHED、0 下载）、前端 ~33s（0 下载）；常规改 src 后后端 package 层重建仅重编译，不再补下 spring-milestones 依赖

---

## 2. 部署（有 docker 的机器，通常是 VM）

### 2.1 前置条件

| 项 | 建议 |
|---|---|
| 内存 | **5G**（容器上限合计 ~2.9G，留系统余量） |
| swap | 2G（保险，防偶发 OOM） |
| 磁盘 | 30G（实际用量 ~10G） |
| Docker Engine | ≥20.10 + compose 插件（`docker compose version` 可用） |
| 镜像加速器 | 已配（拉 mysql/redis/rabbitmq 官方镜像更快，`/etc/docker/daemon.json` + `systemctl restart docker`） |

> 内存不足的补救：给 app 加 `mem_limit` 后 JVM 会自动把堆压到 800MB，本 compose 已全部配置好，无需再手动调整。

### 2.2 登录 ACR + 拉取镜像

```bash
docker login --username=<你的ACR用户名> <你的ACR地址>
# 密码 = 阿里云容器镜像服务 → 访问凭证页设置的 registry 密码（不是账号登录密码）

cd /mnt/hgfs/heima/picknear/picknear   # 或部署目录（见 §3）
docker compose pull                     # 拉取 ACR 两个镜像（app/frontend latest）
docker compose up -d --no-build         # --no-build 强制用已拉镜像，避免触发本地构建
```

> ⚠️ 不常开的 VM 每次开机后先 `docker compose pull` 再 `up`，确保拿到最新镜像。

---

## 3. 部署目录与 .env

compose 从 **compose 文件同目录**的 `.env` 读取变量。后端实际引用的环境变量有 4 个：

| 变量 | 值来源 | 说明 |
|---|---|---|
| `DB_PASSWORD` | 自定义 | MySQL root 密码，**必须与 `heima-init.sql` 或现有库一致** |
| `DASHSCOPE_API_KEY` | `E:\heima\picknear\默认业务空间-apiKey-6132121.csv`（apiKey 字段，`sk-ws-...`） | 通义千问 AI |
| `OSS_ACCESS_KEY_ID` | 阿里云控制台 → RAM 访问密钥 | 图片上传 |
| `OSS_ACCESS_KEY_SECRET` | 同上 | 图片上传 |

> `OSS_ENDPOINT` / `bucket` / `region` 已硬编码在 `application-prod.yaml`（cn-beijing / ntwitm1）。Langfuse 4 个变量缺一 app 启动即崩（见 `服务器镜像部署指南.md` §4）。`.env` 含密钥，**不要提交进 git**（`.gitignore` 已忽略）。

```bash
cd /mnt/hgfs/heima/picknear/picknear
cp .env.example .env    # 然后编辑填入真实值
```

**任意新机器部署**都需要一份目录布局（compose 用了相对路径 bind mount，含前端 `../../` 路径，见 `服务器镜像部署指南.md` §3）：

```
<部署根>/
├── picknear/
│   ├── docker-compose.yml              # ① compose 文件
│   ├── .env                            # ② 密钥 + 全部配置
│   └── src/main/resources/db/
│       └── heima-init.sql              # ③ MySQL 首次初始化脚本
└── nginx-1.18.0heima/
    └── frontend/
        └── nginx.conf                  # ④ 前端 nginx 配置
```

> ⚠️ 服务器上**不需要** Dockerfile / src / 前端源码（构建源），`--no-build` 直接用镜像。

---

## 4. 更新流程（代码改动后）

```
主机改代码 → git commit/push
  ├─ 后端 feature 分支：push 不触发生成；合并到 master → 自动构建 app 镜像 → ACR
  └─ 前端 master 分支：push → 自动构建 frontend 镜像 → ACR
VM：docker compose pull && docker compose up -d --no-build
```

- 前后端镜像更新**相互独立**，各自动构建各自完成即可，无依赖关系
- 更新 app 后看 `docker compose logs -f app` 确认启动正常
- 想跳过自动构建手动控制版本：用 `workflow_dispatch` 指定 tag，然后 VM 改 compose 的 image tag 后 pull（正常用 `latest` 即可）

---

## 5. 验证

```bash
# 查看全部容器状态（应全部 Up，healthcheck 渐次通过）
docker compose ps

# 后端健康检查（等待 mysql/redis/rabbitmq 变 healthy 后 app 才启动）
docker compose logs -f app

# 页面访问
curl -I http://localhost:48080            # 应返回 200 / index.html

# API 访问（走 nginx 反向代理）
curl http://localhost:48080/api/...       # 应返回后端 JSON
```

浏览器访问：`http://<虚拟机IP>:48080`

---

## 6. 运维

| 操作 | 命令 |
|---|---|
| 查看日志 | `docker compose logs -f app` / `-f frontend` |
| 重启某服务 | `docker compose restart app` |
| 查看健康状态 | `docker compose ps` |
| 进入容器 | `docker compose exec app sh` |
| 更新前端配置 | 改 nginx.conf → `docker compose exec frontend nginx -s reload` |
| 关闭全部 | `docker compose down`（保留数据卷） |
| 清空重建 | `docker compose down -v`（**删除数据卷**，慎用） |

---

## 7. 常见问题排查

| 现象 | 原因 | 解决 |
|---|---|---|
| `docker compose up` 报 `required variable DB_PASSWORD is missing` | `.env` 缺失或变量为空 | 确认 `.env` 存在且有值（§3） |
| `unauthorized: authentication required`（拉镜像时） | 未登录 ACR | 重新 `docker login`（§2.2） |
| 拉官方镜像超时 | 加速器未生效 | 检查 daemon.json → `systemctl restart docker` |
| app 崩溃 `Could not resolve placeholder 'LANGFUSE_BASE_URL'` | `.env` 缺 LANGFUSE 4 个变量 | 补上真实 Langfuse 云值 |
| 构建镜像报 `unknown manifest class for application/vnd.oci.empty.v1+json` | 开了 provenance/sbom | `build-image.yml` 已配 `provenance: false, sbom: false`；本地改回 |
| VM 启动报端口被占 | 宿主机 8080/8082 之类已被占用 | 本机停掉 nginx-1.18.0（占 8080） |
| 容器 OOM | 内存不够 | 给 VM 加内存/swap；确认各服务 `mem_limit` 已配 |
| `app` 一直不 healthy | 依赖的 mysql/redis/rabbitmq 未就绪 | `docker compose logs app` 看连接报错，先等依赖 healthy |
| 前端能开但接口 502 | nginx.conf 挂载未生效 / 后端未起 | 检查 `/etc/nginx/conf.d/default.conf` 内容，`nginx -s reload` |
| VM 挂起/恢复后容器间网络全断（`NoRouteToHostException: Host is unreachable`） | VMware 挂起后 Docker 网桥 `br-*` 停在 DOWN、宿主路由表丢网段路由 | `sudo systemctl restart docker`（容器 `restart: unless-stopped` 自动拉起）；详见 §8 |

### 7.1 VM 挂起/恢复导致容器间网络全断

**背景**：VMware 挂起虚拟机再恢复后，Docker 自定义网络（`picknear-net`）的网桥接口可能停留在 DOWN 状态，导致所有容器间通信中断（2026-08-02 实际踩到）。容器自身全活着、healthcheck 全 healthy（都是 localhost 自检，不走网桥），非常隐蔽。

**现象**：`app` 日志持续报 RabbitMQ 连接失败：

```
o.s.a.r.l.SimpleMessageListenerContainer - Failed to check/redeclare auto-delete queue(s).
Consumer raised exception, processing can restart if the connection factory supports it
org.springframework.amqp.AmqpIOException: java.net.NoRouteToHostException: Host is unreachable
```

**判断特征**（逐条核查）：
1. 配置正确但连不上：`SPRING_RABBITMQ_HOST=rabbitmq`、容器同网络、`docker exec app getent hosts rabbitmq` 能解析出 IP（Docker 内嵌 DNS 不走网桥，解析正常 ≠ 网络通）
2. 跨容器 TCP/ping 全断：`nc -zv <服务名> <端口>` 报 Host is unreachable，ping 100% 丢包
3. 网桥 DOWN：`ip link show` 中 `br-<id>` 为 `state DOWN / NO-CARRIER`（**挂了 veth 却 NO-CARRIER 即异常**；`docker0` 无容器时 DOWN 是正常状态，别误判）
4. 宿主路由表缺网段路由：`ip route | grep 172.18` 无输出

**修复**（需 root，任选其一）：

```bash
# 推荐：重启 docker，dockerd 重建所有网桥/路由，容器自动拉起
sudo systemctl restart docker

# 或最小操作：只拉起网桥并补路由
sudo ip link set br-<id> up
sudo ip route add 172.18.0.0/16 dev br-<id> src 172.18.0.1
```

> ⚠️ **可能比 link DOWN 更严重（2026-08-05 实测）**：网桥是 UP 的，但**网桥 IP（`172.18.0.1/16`）整个丢了**、路由表缺网段、部分容器 veth 脱开。此时直接 `ip route add` 会报 `Invalid prefsrc address`（因为 src 地址不存在）；需先 `sudo ip addr add 172.18.0.1/16 dev br-<id>`，而**脱开的 veth 手工补不回来，只能重启 docker**。所以遇到 `Invalid prefsrc` 就别手工修了，直接重启 docker。

恢复后 `docker exec app nc -zv rabbitmq 5672` 应通，RabbitMQ 监听会自动重连。

**预防**：本 VM 已配 `docker-bridge-monitor.timer` 自动检测（每 2 分钟，网桥不健康即重启 docker，逻辑见 `vm-docs/docker-bridge-monitor/`）。挂起/恢复后若容器间通信异常，等 2 分钟让监控自愈，或手动 `sudo systemctl restart docker`；不要逐个容器排查（配置往往是对的）。

---

## 8. 历史记录：本机构建方式（勿再使用）

以下为 2026-08 期间的本机构建/传输流程，**已被 CI/CD + ACR 取代，不再使用**。保留作为排障参考（尤其 `build.sh` 与 HGFS 的坑）：

- 2026-08-03 曾因 `COMPOSE_FILE` 指向 `/opt/picknear` compose 持续构建旧代码
- 2026-08-05 在 VM 直接 `docker build` 会踩 HGFS `short read / unexpected EOF`，曾用 `/home/docker/picknear-build/build.sh`（本地磁盘构建）
- 2026-08-04 ACR 推送须 `--provenance=false --sbom=false`，否则 `docker push` 报 unknown manifest class
- 2026-08-12 构建缓存清理三层防护（daemon GC / build.sh prune / 每日 cron `maintenance.sh`）
- 基础镜像被 `docker prune` 清掉后要先 `docker pull` 拉回再构建（VM 直连 Docker Hub 不通，从可用 mirror 拉后 `docker tag`）

---

*最后更新：2026-09-02(构建缓存优化：注释 spring-milestones、前端跳过 vue-tsc、registry 兜底被 ACR 拒绝) · 端口：原端口 + 40000 · 镜像 tag：`picknear-app:latest` / `picknear-frontend:latest`*