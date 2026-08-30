# PickNear（探点）— 高并发点评平台

> 基于黑马点评（hm-dianping）二次开发，融合 AI 智能助手的高并发点评平台。

## 技术栈

| 层 | 技术 |
|---|------|
| 后端框架 | Spring Boot 3.4.4 + Java 17 + Maven |
| 数据库 | MySQL 8.0 + MyBatis-Plus 3.5 |
| 缓存 | Caffeine（本地）+ Redis 6（Lua / 分布式锁） |
| 消息队列 | RabbitMQ（Confirm + 幂等消费） |
| AI 智能助手 | Spring AI 1.1.2 + DashScope（通义千问）+ SSE 流式推送 |
| 可观测性 | Micrometer + OpenTelemetry + OTLP → Langfuse |
| 文件存储 | 本地文件 / 阿里云 OSS（@Profile 切换） |
| 运维 | Docker Compose + Nginx 反向代理 |

## 项目结构

```
picknear/                        # 后端（Spring Boot，端口 8081）
├── agent/                       # 🤖 AI 智能助手模块
│   ├── orchestration/           #   多轮编排（TaskPlanner → MultiRoundOrchestrator）
│   ├── plan/                    #   规划层（PlanRouter 策略 / DAG 执行计划）
│   ├── execution/               #   执行层（ToolExecutionFacade / 策略模式）
│   ├── guard/                   #   工具安全守卫（限流 / 高危拦截 / 参数校验）
│   ├── hook/                    #   Prompt 预处理 Hook 链（注入检测 / 敏感词）
│   ├── permission/              #   数据级权限（AOP + 注解驱动）
│   ├── observability/           #   全链路观测（Micrometer → OTel → Langfuse）
│   ├── prompt/                  #   提示词管理（Langfuse 远程 → 本地DB → 内置）
│   ├── tool/                    #   工具注册与定义
│   └── subagent/                #   子 Agent 执行（回调 / 结果压缩）
├── auth/                        # 🔐 认证模块（双 Token + 密码登录）
├── content/                     # 📝 内容模块（博客 / Feed 流）
├── shop/                        # 🏪 商户模块
├── voucher/                     # 🎫 优惠券模块（高并发库存扣减）
├── user/                        # 👤 用户模块
├── config/                      # 全局配置
├── common/                      # 公共组件（缓存 / 幂等 / 分布式锁）
└── utils/                       # 工具类（Redis / 安全 / 缓存）
```

## 核心亮点

### 1. AI 智能助手

- **子任务规划与异步执行**：TaskPlanner 三阶段循环（decompose → execute → merge），AI 规划 + Java 三层校验兜底；MultiRoundOrchestrator 最多 5 轮迭代
- **工具调用安全守卫**：多策略投票制 ToolGuardManager（RateLimitPolicy 令牌桶限流 + HighRiskListPolicy 高危拦截 + PatternMatchPolicy 参数校验），配合 `@RequiredDataPermission` AOP 切面做数据级权限校验
- **Prompt 预处理 Hook 链**：责任链串行执行 InjectionDetectHook + SensitiveWordHook，任一 BLOCK 短路阻断，Fail-Open 降级
- **SSE 流式响应与容错**：SseEmitter 逐 token 推送 + 阶段事件；AfterAiHookChain 后处理 + AiResponseRouter 二次路由
- **提示词管理**：Langfuse 远程 → 本地数据库 → 内置模板三级降级

### 2. 双 Token 认证体系

- **Access Token（30min）+ Refresh Token（7天）**：每次刷新生成新 Refresh Token，旧 Token 一次性使用
- **版本号控制**：添加 version 字段，严格校验 Token 组合有效性，防止过期 Token 滥用
- **原子刷新**：Redis Lua 脚本原子执行检查、删除旧 Token、写入新 Token
- **单设备登录**：Redis 存储用户当前有效 Token，新登录失效旧 Token
- **多级缓存**：Caffeine 本地缓存快速拒绝 + Redis 最终校验，减少网络开销
- **密码登录**：BCrypt 加密 + 账户锁定（10 次失败锁 30 分钟）

### 3. 高并发库存扣减

- **Redis 预减库存**：Lua 脚本原子执行库存检查、扣减、重复校验
- **异步落库**：RabbitMQ Confirm + Return 机制可靠投递，业务与数据库解耦
- **分布式 ID**：Redis 雪花算法 + 预生成队列

### 4. 高并发问题排查与优化

| 问题 | 原因 | 解决方案 | 效果 |
|------|------|----------|------|
| Lua 脚本锁竞争 | `getSha1()` synchronized 懒加载 | 继承 DefaultRedisScript 预计算 SHA1 | BLOCKED 线程显著减少 |
| 同步日志导致 Full GC | 5 分钟输出 700MB 日志 | 异步日志 AsyncAppender，队列 2048 | Full GC 472→0 |
| Redis 连接池过小 | 800 Tomcat 线程配 16 连接 | max-active 16→200 | QPS 更稳定 |
| 网络 IO 延迟 | 非本机部署网络 35ms | Caffeine 本地缓存 | 减少 Redis 访问 |

### 5. 其他优化

- **多级缓存**：Caffeine → Redis → MySQL 逐级降级
- **批量缓存更新**：定时批量刷新，Redis 筛除已有 + MySQL 查询
- **点赞异步处理**：Redis 缓存 + 定时批量写入
- **全链路观测**：Micrometer Observation → OpenTelemetry → OTLP → Langfuse 云端可视化

## 快速启动

```bash
# 1. 启动基础设施
docker run -d -p 6379:6379 redis:alpine
docker run -d -p 3306:3306 -e MYSQL_ROOT_PASSWORD=root mysql:8.0
docker run -d -p 5672:5672 -p 15672:15672 rabbitmq:management-alpine

# 2. 配置数据库（创建表 + 初始化数据）
#    SQL 脚本见 src/main/resources/

# 3. 配置 application.yml 中的连接信息

# 4. 启动
cd picknear/
mvn spring-boot:run
# 或 IDE 直接运行 PickNearApplication
```

## 压测结果

Docker 部署 Redis/RabbitMQ + 本机 Spring Boot 空接口压测：
- **JMeter**：5500 TPS
- **wrk**：16000 TPS

## 关键文件

| 功能 | 文件 |
|------|------|
| 多轮编排器 | `agent/orchestration/MultiRoundOrchestrator.java` |
| 工具安全守卫 | `agent/guard/ToolGuardManager.java` |
| Prompt Hook 链 | `agent/hook/HookChain.java` |
| 全链路观测 | `agent/observability/api/AgentTracer.java` |
| 双 Token 刷新 Lua | `src/main/resources/refreshToken.lua` |
| 过期 Token 续期 Lua | `src/main/resources/refreshExpiredToken.lua` |
| 库存扣减 Lua | `src/main/resources/MqSeckill.lua` |
| 认证拦截器 | `interceptor/RefreshTokenInterceptor.java` |
| 批量缓存加载 | `utils/cache/BatchLoadCache.java` |
| 分布式 ID 生成 | `utils/redis/RedisIdWorker.java` |

## License

Private — 仅供学习与展示。
