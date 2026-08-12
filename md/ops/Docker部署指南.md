# PickNear Docker 部署指南

> 前后端分离（Vue + Spring Boot），通过 Docker 镜像 + docker compose 部署到 Linux 虚拟机。
>
> **架构**：nginx（前端静态 + 反向代理）→ Spring Boot 后端 → MySQL / Redis / RabbitMQ

> ## ⚠️ 开发模式说明（2026-08-03 更新）
>
> 本指南的 scp + `/opt/picknear` 部署根流程是**外网 VPS 部署模式**。
> **当前开发/演示 VM 不用这套**：开发一律在共享文件夹 `/mnt/hgfs/heima`（主机侧 `E:\heima`）
> 进行，`~/.bashrc` 的 `COMPOSE_FILE` 已指向共享文件夹的
> `/mnt/hgfs/heima/picknear/picknear/docker-compose.yml`，部署用 `docker compose up -d --no-build`。
> ⚠️ **构建镜像不要直接 `docker compose build` / `docker build`**——共享文件夹 hgfs 会被 BuildKit
> 读爆 `short read / EOF`，一律用 `/home/docker/picknear-build/build.sh` 本地磁盘构建（见 §2.3 第 6 条）。
>
> - `/opt/picknear` 是 2026-08-01 按本指南流程创建的一次性部署副本（配套 `vm-docs/deploy-vm.sh`，VM 部署文档已归档到 `vm-docs/`），**静态拷贝、不随主机更新**
> - 踩坑史：2026-08-03 曾因 `COMPOSE_FILE` 指向 `/opt/picknear`，compose 持续构建旧代码（缺失 observability 模块）
> - 若再次使用 VPS 部署模式（scp 到远端机器），仍按本指南执行；本机 VM 一律走共享文件夹直连

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
| app | `picknear-app:latest` | 8082 | **48082** | 1g |
| frontend | `picknear-frontend:latest` | 80 | **48080** | 128m |

> ⚠️ 端口全部改为非常用值（原端口 + 40000），仅宿主机暴露侧改，容器内服务间仍走内部端口直连，互不影响。

---

## 1. 前置条件

### 本机（构建机）
- Docker Desktop（建议开启 **BuildKit** 以使用 `--mount=type=cache` 加速）
- 已配置镜像加速器：`C:\Users\<你>\.docker\daemon.json`
  ```json
  {
    "builder": { "gc": { "defaultKeepStorage": "20GB", "enabled": true } },
    "registry-mirrors": [
      "https://9lod8w6s.mirror.aliyuncs.com",
      "https://docker.m.daocloud.io",
      "https://docker.1panel.live"
    ]
  }
  ```
- **改完 daemon.json 必须重启 Docker Desktop 才生效**

### 虚拟机（运行机）
| 项 | 建议 |
|---|---|
| 内存 | **5G**（容器上限合计 ~2.9G，留系统余量） |
| swap | 2G（保险，防偶发 OOM） |
| 磁盘 | 30G（实际用量 ~10G） |
| Docker Engine | 已安装并启动 |

> 内存不足的补救：给 app 加 `mem_limit` 后 JVM 会自动把堆压到 800MB，本 compose 已全部配置好，无需再手动调整。

---

## 2. 本机构建镜像

### 2.1 后端镜像

```bash
cd /e/heima/picknear/picknear
docker build -t picknear-app:latest .
```

要点：
- 内部已配置 Maven 国内镜像（阿里云优先）、`/root/.m2` 缓存、跳过测试，二次构建秒级复用依赖
- 产物约 **667MB**（含全部依赖）

### 2.2 前端镜像

```bash
cd /e/heima/nginx-1.18.0heima/frontend
docker build -t picknear-frontend:latest .
```

要点：
- 内部已配置 npm 国内源（npmmirror）、`/root/.npm` 缓存
- 产物约 **74.5MB**（nginx 运行时）

### 2.3 构建注意事项（踩过的坑）

1. **不要管道到 `tail`**：`docker build ... | tail` 会缓冲输出，看起来"卡住"。直接跑看实时进度。
2. **lock 文件必须用容器同版本 npm 生成**：本机 npm 11 与 `node:22-alpine` 的 npm 10 对同一依赖树解析不同，npm ci 会报 `EUSAGE`。
   更新依赖后重建 lock 用：
   ```bash
   cd /e/heima/nginx-1.18.0heima/frontend
   npx npm@10 install --package-lock-only --registry=https://registry.npmmirror.com
   ```
3. 首次构建慢是正常的（下载全部依赖一次）；二次构建走缓存会很快。
4. **推送到阿里云 ACR 必须禁用 attestation**（否则 `docker push` 报
   `unknown manifest class for application/vnd.oci.empty.v1+json`，tag 更新失败，2026-08-04 实测）：
   ```bash
   cd /e/heima/picknear/picknear
   docker buildx build --provenance=false --sbom=false -t picknear-app:latest .
   docker push crpi-.../picknear/picknear-app:latest
   ```
   > 判定：构建日志出现 `exporting attestation manifest` 就是会被阿里云拒的格式；禁用后只有 `exporting manifest`。
5. **后端 Dockerfile 已修复**（Spring Boot 3.4）：分层 COPY 必须平铺到 `/app` 根（层内自带 `META-INF/`、`BOOT-INF/lib/` 前缀），ENTRYPOINT 用 `org.springframework.boot.loader.launch.JarLauncher`。
   完整原因见 `vm-docs/VM部署交接文档.md` §8 修复 1/2/4（VM 部署文档已归档到工作区根 `vm-docs/`）。构建用的源码/构建文件都在共享文件夹 `/mnt/hgfs/heima/picknear/picknear/`。
6. **VM 开发构建必须用 build.sh（本地磁盘）**：共享文件夹 hgfs 在 BuildKit 读 context 时偶发 `short read / unexpected EOF`，直接 `docker compose up -d --build` / `docker build` 会踩中（2026-08-05 实测）。用 `/home/docker/picknear-build/build.sh [app|frontend|all]`：自动 tar 同步源码到本地磁盘 → 本地构建 → 镜像 tag 不变；部署再 `cd /mnt/hgfs/heima/picknear/picknear && docker compose up -d --no-build`。
7. **构建缓存清理（2026-08-12 更新）**：单独 `--max-used-space` 只清"可回收"缓存，被镜像层引用的 in-use 缓存会无限累积（2026-08-12 实测撑到 8GB、reclaimable 仅 1.3GB、磁盘 94%）。现三层防护：
   - **daemon 级 GC**：`/etc/docker/daemon.json` 配 `builder.gc`（`maxUsedSpace: 2GB` + `minFreeSpace: 4GB`），BuildKit 构建后自动 GC，空闲 <4G 时连 in-use 缓存也强制清（注：29 版 `keepStorage` 已移除，字段为 `maxUsedSpace`/`minFreeSpace`）；
   - **build.sh**：构建后 `docker builder prune --max-used-space 2G --min-free-space 4G -f`，失败会明显报错不再静默掩盖；
   - **每日 cron**（docker 用户）：`/home/docker/picknear-build/maintenance.sh` 凌晨 4:17 清缓存 + 守卫式清 7 天前旧镜像（cachetest 类）。缓存清空后首次构建需重新拉基础镜像，属正常。
8. **基础镜像被清后要先拉回再构建**：prune 掉 maven/node 基础镜像层后，下次构建要重新拉。VM 直连 Docker Hub 不通，mirror 对部分层可能挂死（`0B` 卡住不超时，buildkit 不会自动换源）。解决：先 `docker pull maven:3.9-eclipse-temurin-17-alpine`（或从响应快的 mirror 直接拉：`docker pull docker.1panel.live/library/maven:3.9-eclipse-temurin-17-alpine` 成功后 `docker tag` 回原名），构建即可用本地镜像不再走网络。
9. **后端 Dockerfile 缓存优化（2026-08-05）**：用 `COPY --chown=appuser:appgroup` 取代 COPY 后 `chown -R /app`——后者会把 ~200MB 依赖的每个文件属主都改一遍，overlay 只能重写成 ~214MB 新层（同内容存两份，每次构建多一份）。同时预创建 `/app/logs` 并 chown 给 appuser（logback 写 `./logs`，app 需可写）。配置统一走 `.env` 环境变量，不再挂载 `/app/config`。镜像体积 683MB → ~470MB。

---

## 3. 准备 .env 密钥

compose 从 **compose 文件同目录**的 `.env` 读取变量。后端实际引用的环境变量有 4 个：

| 变量 | 值来源 | 说明 |
|---|---|---|
| `DB_PASSWORD` | 自定义 | MySQL root 密码，**必须与 `heima-init.sql` 或现有库一致** |
| `DASHSCOPE_API_KEY` | `E:\heima\picknear\默认业务空间-apiKey-6132121.csv`（apiKey 字段，`sk-ws-...`） | 通义千问 AI |
| `OSS_ACCESS_KEY_ID` | 阿里云控制台 → RAM 访问密钥 | 图片上传 |
| `OSS_ACCESS_KEY_SECRET` | 同上 | 图片上传 |

> `OSS_ENDPOINT` / `bucket` / `region` 已硬编码在 `application-prod.yaml`（cn-beijing / ntwitm1），无需配置。

创建 `.env`（可参考 `.env.example`）：

```bash
cd /e/heima/picknear/picknear
cp .env.example .env
```

然后编辑 `.env`，填入真实值。**compose 全部走环境变量、无默认值** —— `DB_PASSWORD` 缺失会直接报错（`required variable DB_PASSWORD is missing a value`），不会静默用默认密码，因此本机与 VM 各需一份：

```bash
DB_PASSWORD=281458

OSS_ACCESS_KEY_ID=你的AccessKeyId
OSS_ACCESS_KEY_SECRET=你的AccessKeySecret

DASHSCOPE_API_KEY=sk-ws-你的Key
```

> ⚠️ `.env` 含密钥，**不要提交进 git**（`.gitignore` 已忽略）。

---

## 4. 导出并传输镜像

### 4.1 本机导出（合并压缩，节省传输）

```bash
cd /e/heima/picknear/picknear
docker save picknear-app:latest picknear-frontend:latest | gzip > picknear-images.tar.gz
ls -lh picknear-images.tar.gz   # 约 200~300MB
```

### 4.2 传输到虚拟机

```bash
# 方式一：scp（推荐，VPS 用户）
scp picknear-images.tar.gz root@<虚拟机IP>:/opt/picknear/

# 方式二：先传到你手边，再手动拷入虚拟机
```

---

## 5. 部署到虚拟机

### 5.1 目录结构（必须与本机一致，compose 用了相对路径 bind mount）

compose 依赖 4 个相对路径文件，**VM 上必须存在**：

```
/opt/picknear/                          # VM 部署根目录
├── picknear/                           # 与工作区同层级结构
│   ├── docker-compose.yml              # ① compose 文件
│   ├── .env                            # ② 密钥 + 全部配置（统一走环境变量）
│   └── src/main/resources/db/
│       └── heima-init.sql              # ③ MySQL 首次初始化脚本
└── nginx-1.18.0heima/
    └── frontend/
        └── nginx.conf                  # ④ 前端 nginx 配置
```

把这 4 项从本机拷贝到 VM（保持相对路径）：

```bash
# VM 上创建骨架
mkdir -p /opt/picknear/{picknear/src/main/resources/db,nginx-1.18.0heima/frontend}

# 本机 scp 对应文件（在 e:\heima 下执行）
scp picknear/picknear/docker-compose.yml                       root@<IP>:/opt/picknear/picknear/
scp picknear/picknear/.env                                     root@<IP>:/opt/picknear/picknear/
scp picknear/picknear/src/main/resources/db/heima-init.sql     root@<IP>:/opt/picknear/picknear/src/main/resources/db/
scp nginx-1.18.0heima/frontend/nginx.conf                      root@<IP>:/opt/picknear/nginx-1.18.0heima/frontend/
```

> 也可用 `scp -r` 整目录拷贝，再删掉 VM 上不需要的（.git/target/node_modules）。

### 5.2 加载镜像

```bash
# VM 上
cd /opt/picknear
gzip -dc picknear-images.tar.gz | docker load
docker images | grep picknear   # 确认两个镜像已加载
```

### 5.3 启动

```bash
cd /opt/picknear/picknear

# 语法校验（可选）
docker compose config

# 启动：--no-build 强制使用已加载镜像，避免 VM 上触发构建
docker compose up -d --no-build
```

> 首次启动会创建 MySQL 数据卷并执行 `heima-init.sql` 初始化。

---

## 6. 验证

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

## 7. 运维

| 操作 | 命令 |
|---|---|
| 查看日志 | `docker compose logs -f app` / `-f frontend` |
| 重启某服务 | `docker compose restart app` |
| 查看健康状态 | `docker compose ps` |
| 进入容器 | `docker compose exec app sh` |
| 更新前端配置 | 改 nginx.conf → `docker compose exec frontend nginx -s reload` |
| 关闭全部 | `docker compose down`（保留数据卷） |
| 清空重建 | `docker compose down -v`（**删除数据卷**，慎用） |

**更新流程**（代码改动后）：
1. 本机重新构建镜像 → `docker save` → 传输
2. VM：`gzip -dc xxx | docker load`
3. `docker compose up -d --no-build`（镜像标签相同会自动替换）

---

## 8. 常见问题排查

| 现象 | 原因 | 解决 |
|---|---|---|
| `npm ci` 报 `EUSAGE` | lock 与 package.json 不同步 / npm 版本不一致 | §2.3：用 `npx npm@10 install --package-lock-only` 重建 |
| 构建慢 | 未走国内源 / 未加缓存 / 加速器未生效 | 确认 daemon.json 配加速器并**重启 Docker Desktop** |
| 构建"卡住" | `| tail` 缓冲了输出 | 去掉管道直接跑 |
| VM 启动报端口被占 | 宿主机 8080/8082 之类已被占用 | 本机停掉 nginx-1.18.0（占 8080） |
| 容器 OOM | 内存不够 | 给 VM 加内存/swap；确认各服务 `mem_limit` 已配 |
| `app` 一直不 healthy | 依赖的 mysql/redis/rabbitmq 未就绪 | `docker compose logs app` 看连接报错，先等依赖 healthy |
| 前端能开但接口 502 | nginx.conf 挂载未生效 / 后端未起 | 检查 `/etc/nginx/conf.d/default.conf` 内容，`nginx -s reload` |
| VM 挂起/恢复后容器间网络全断（`NoRouteToHostException: Host is unreachable`） | VMware 挂起后 Docker 网桥 `br-*` 停在 DOWN、宿主路由表丢网段路由 | `sudo systemctl restart docker`（容器 `restart: unless-stopped` 自动拉起）；详见 §8.1 |

### 8.1 VM 挂起/恢复导致容器间网络全断

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

*最后更新：2026-08-05（§2.3 增补 build.sh 本地构建/缓存清理/基础镜像拉取/chown 优化；§8.1 增补网桥 IP 丢失与监控自愈）· 端口方案：当前端口 + 40000 · 镜像 tag：`picknear-app:latest` / `picknear-frontend:latest`*
