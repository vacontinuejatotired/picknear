# picknear 项目架构文档

> 基于探点 (picknear) 的二次开发与高并发优化项目
> 最后更新：2026-07-09

---

## 一、项目概览

| 项目 | 说明 |
|------|------|
| **名称** | picknear（高并发点评平台） |
| **技术栈** | Spring Boot 3.4.4 + MyBatis-Plus + MySQL + Redis + RabbitMQ + Caffeine |
| **JDK** | 17 |
| **构建工具** | Maven |
| **核心目标** | 用户认证重构（双Token+版本号）、秒杀库存扣减优化（Redis+Lua+Mq异步落库）、多级缓存架构 |

---

## 二、项目结构

```
picknear/
├── picknear/                     # Maven 模块（主代码）
│   ├── pom.xml                      # 依赖管理（Spring Boot 3.4.4, JDK 17）
│   └── src/
│       ├── main/
│       │   ├── java/com/hmdp/       # 主代码
│       │   └── resources/           # 配置文件 + Lua脚本
│       └── test/                    # 测试代码
├── md/                              # 设计文档 + 图片
├── logs/                            # 运行时日志
├── target/                          # Maven 构建产物
├── reasonix.toml                    # AI 辅助配置
└── README.md                        # 项目说明
```

---

## 三、包层次与模块职责

### 3.1 入口
- **`HmDianPingApplication.java`** — Spring Boot 启动类，启用 `@EnableAspectJAutoProxy(exposeProxy=true)` 和 `@EnableScheduling`

### 3.2 配置层（config/）

| 配置类 | 职责 |
|--------|------|
| `MvcConfig.java` | 注册拦截器：`RefreshTokenInterceptor`(order=0) 放公开接口+全路径，`LoginInterceptor`(order=1) 拦截需登录路径 |
| `CacheConfig.java` | Caffeine 本地缓存：`userinfoCache`（用户信息，20min TTL，10min 异步刷新）、`tokenValidVersionCache`（Token版本号，5min TTL） |
| `RedisLuaConfig.java` | 预加载 6 个 Lua 脚本到 `DefaultRedisScript`（`LockFreeRedisScript` 封装，启动时计算 SHA1） |
| `RabbitConfig.java` | RabbitMQ：普通/死信/备用交换机 + 队列声明，Jackson JSON 序列化，手动 ACK |
| `CorsConfig.java` | CORS 跨域配置，暴露 authorization/Refresh-Token 响应头 |
| `JwtConfig.java` | JWT 配置 Bean |
| `CookieConfig.java` | RefreshToken Cookie 配置（Secure/SameSite） |
| `RedissonConfig.java` | Redisson 分布式锁客户端配置 |
| `SchedulingThreadConfig.java` | 定时任务线程池配置 |
| `OssConfig.java` / `OssProperties.java` | 阿里云 OSS 配置 |
| `MybatisConfig.java` | MyBatis-Plus 分页插件 |
| `AgentConfig.java` | AI Agent 配置 |
| `WebExceptionAdvice.java` | 全局异常处理，捕获所有异常返回 JSON |

### 3.3 控制层（controller/）

| Controller | 路由前缀 | 关键接口 |
|------------|----------|----------|
| `UserController` | /user | POST /login, /logout, /code, PUT /password, /profile, GET /me, /{id}, POST /sign |
| `VoucherOrderController` | /voucher-order | POST seckill/{id} 查询能否秒杀, POST seckill/saveOrder/{id} 执行下单 |
| `VoucherController` | /voucher | 优惠券增删改查 |
| `ShopController` | /shop | 店铺查询/修改、按距离查询 |
| `ShopTypeController` | /shop-type | 店铺类型列表 |
| `BlogController` | /blog | 博客 CRUD、点赞、热门、关注推送 |
| `BlogCommentsController` | /blog-comments | 博客评论 |
| `FollowController` | /follow | 关注/取关、共同关注 |
| `ChatController` | /chat | 聊天功能 |
| `UploadController` | /upload | 文件上传（OSS/本地） |
| `TestCostTimeController` | /test | 性能测试接口 |

### 3.4 服务层（service/）

| 接口 | 实现 | 职责 |
|------|------|------|
| `AuthService` | `AuthServiceImpl` | 双Token生成/校验/刷新/吊销、验证码原子消费、Token版本号管理（✅ P2 已拆分为 `auth` 域：TokenService/SessionContextService/VerifyCodeService/PasswordService + LoginStrategy 策略族 + UserAccountService，AuthServiceImpl 已删除） |
| `IUserService` | `UserServiceImpl` | 登录/注册/登出、密码修改、签到(BitMap)、个人资料编辑（✅ 登录域已下沉 `auth` 域，`user` 域保留用户行为） |
| `IUserInfoService` | `UserInfoServiceImpl` | 用户详细信息 |
| `IVoucherOrderService` | `MqVoucherOrderServiceImpl` (@Primary) | 秒杀下单：Redis+Lua预扣库存 + RabbitMQ异步落库（✅ P1 已拆为 `voucher` 域：SeckillOrderService 编排 + stock/order/mq 三组件 + AbstractMqConsumer 模板） |
| `ISeckillVoucherService` | `SeckillVoucherServiceImpl` | 秒杀券库存管理（基础CRUD） |
| `IVoucherService` | `VoucherServiceImpl` | 优惠券管理 |
| `IShopService` | `ShopServiceImpl` | 店铺多级缓存、防缓存穿透/击穿、按GEO距离查询 |
| `IShopTypeService` | `ShopTypeServiceImpl` | 店铺类型缓存 |
| `IBlogService` | `BlogServiceImpl` | 博客、点赞、关注推送(Feed收件箱) |
| `IFollowService` | `FollowServiceImpl` | 关注关系 |
| `FileService` | `LocalFileServiceImpl` / `OssFileServiceImpl` | 文件上传（@Profile 切换） |
| `AiService` | `AiServiceImpl` | AI 服务 |
| `ITestService` | `TestServiceimpl` | 测试服务 |

### 3.5 拦截器层（interceptor/）

- **`RefreshTokenInterceptor`** — 拦截所有请求（除公开接口），做 Token 校验 + 自动续期。流程：检查 Authorization 头 -> JWT 解析 -> Caffeine 快速版本身份校验 -> Redis 最终校验 -> 需刷新则分布式锁 + 委托 AuthService 刷新 -> 写回响应头 + Cookie
- **`LoginInterceptor`** — 拦截需登录接口，检查 UserHolder.getUserId() 是否为空

### 3.6 DTO 层

| DTO | 说明 |
|-----|------|
| `Result` | 统一响应体：success/errorMsg/data/total |
| `LoginFormDTO` | 登录表单（phone/code/password） |
| `TokenPair` | 双Token对：accessToken/refreshToken/version |
| `ValidationResult` | Token校验结果：valid/userId/version/needsRefresh |
| `LuaResult` | Lua 返回解析（code/message） |
| `UserDTO` | 用户信息 DTO |
| `PasswordChangeDTO` | 密码修改 DTO |
| `ProfileUpdateDTO` | 个人资料更新 DTO |
| `ScrollResult` | 滚动分页结果 |

### 3.7 实体层（entity/）

| Entity | 对应表 | 关键字段 |
|--------|--------|----------|
| `User` | tb_user | id/phone/password/nickName/icon |
| `UserInfo` | tb_user_info | userId/nickName/icon/city/introduce |
| `Shop` | tb_shop | id/name/typeId/images/area/address/avgCost/score |
| `ShopType` | tb_shop_type | id/name/icon/sort |
| `Voucher` | tb_voucher | id/shopId/title/subTitle/rules/payValue/actualValue/type/status |
| `SeckillVoucher` | tb_seckill_voucher | voucherId/stock/beginTime/endTime |
| `VoucherOrder` | tb_voucher_order | id/userId/voucherId/payType/status/createTime |
| `Blog` | tb_blog | id/shopId/userId/title/images/content/liked/comments |
| `BlogComments` | tb_blog_comments | id/userId/blogId/parent/content/liked |
| `Follow` | tb_follow | id/userId/followUserId/followTime |
| `TokenVersionCache` | — | Caffeine 本地 Token 版本缓存实体 |
| `UserinfoCache` | — | Caffeine 用户信息缓存实体 |
| `SnowflakeIdQueue` | — | 分布式 ID 预生成队列（阻塞队列） |

### 3.8 工具层（utils/）

| 工具类 | 职责 |
|--------|------|
| `UserHolder` | ThreadLocal 存储当前用户ID + UserDTO |
| `cache/CacheClient` | 通用缓存工具：防缓存穿透/击穿（逻辑过期+互斥锁） |
| `cache/BatchLoadCache` | 异步批量加载用户信息到 Redis（定时 2s 轮询，pipeline 批量读写） |
| `cache/CaffeineConstants` | 本地缓存配置常量 |
| `cache/CacheMonitor` | 缓存监控 |
| `cache/RedisData` | 缓存数据实体（含过期时间） |
| `redis/RedisIdWorker` | Redis 雪花算法 ID 生成 + ID 预生成队列 |
| `redis/RedisConstants` | 所有 Redis Key 前缀与 TTL 常量 |
| `redis/ILock` | 分布式锁接口 |
| `redis/SimpleRedisLock` | 基于 SETNX 的简单分布式锁 |
| `redis/LockFreeRedisScript` | 免锁 Lua 脚本封装（预计算 SHA1，启动加载） |
| `security/JwtUtil` | JWT 生成/解析（HS256） |
| `security/PasswordEncoder` | BCrypt 密码加密/验证 |
| `constants/RabbitMqConstants` | RabbitMQ 常量 |
| `constants/SystemConstants` | 系统常量 |
| `constants/RegexPatterns` | 正则表达式模式 |
| `RegexUtils` | 手机号/密码格式校验 |
| `TokenTestUtil` | Token 测试工具 |

### 3.9 注解/切面

- `@RecordTime` — 方法计时注解
- `AutoRecordTime` — 切面实现，记录方法耗时并日志输出

---

## 四、核心业务流程

### 4.1 用户认证（双Token + 版本号）

```
登录请求
    ↓
UserController/login -> UserServiceImpl.login()
    ↓ 委托 AuthService.generateTokenPair(userId)
    ↓
1. RedisIdWorker.nextVersion(userId) -> 生成新版本号
2. JwtUtil.generateToken(userId, version) -> accessToken
3. UUID -> refreshToken
4. Redis Lua 原子写入 4 个 Key:
   - login:token:access:{userId} -> accessToken (30min)
   - login:token:refresh:{userId} -> refreshToken (7天)
   - token:version:valid:{userId} -> version (7天)
   - token:version:current:{userId} -> version (自增, 8天)
5. accessToken 回写 authorization 响应头
   refreshToken 回写 httpOnly Cookie
```

#### Token 校验流程（RefreshTokenInterceptor）

```
请求携带 Authorization: Bearer xxx + refresh_token Cookie
    ↓
1. 提取 accessToken
2. JwtUtil 解析 claims -> 获取 userId + version
   - 成功 -> 继续
   - 过期(ExpiredJwtException) -> isExpired=true, claims仍可读
   - 其他异常 -> 返回 401
    ↓
3. Caffeine 快速版本身份校验
   - 命中且版本匹配 -> valid=true -> 放行
   - 命中但版本不匹配 -> 直接 401
   - 未命中 -> 走 Redis
    ↓
4. Redis 校验 token:version:valid:{userId}
   - 版本匹配 -> 更新 Caffeine -> valid=true
   - 版本不匹配 -> 更新 Caffeine -> 无效
   - Key 不存在 -> 视为新登录
    ↓
5. 如需刷新 -> 分布式锁 + AuthService.refreshTokenPair()
   - 临期刷新（JWT未过期）：RefreshToken.lua -> 保持版本号
   - 过期刷新（JWT已过期）：RefreshExpiredToken.lua -> bump version
   - 刷新成功 -> 写回 authorization 头 + Set-Cookie
```

### 4.2 秒杀下单（Redis+Lua+MQ）

```
POST /voucher-order/seckill/saveOrder/{id}
    ↓
MqVoucherOrderServiceImpl.saveOrder(voucherId)
    ↓
1. RedisIdWorker.getIdFromQueue() -> 预生成分布式ID
    ↓
2. 执行 MqSeckill.lua（原子操作）
   - 检查 seckill:stock:{voucherId} 是否存在
   - 检查 seckill:order:{voucherId} 是否已下单
   - DECR 库存 -> 若 <0 则 INCR 恢复
   - SADD 记录用户 -> 存储订单信息
   - 返回 200/50x/51x 状态码
    ↓
3. 构建 VoucherOrder 对象
4. 保存订单到 MySQL（同步落库）
5. RabbitMQ 发送消息（异步扣减库存）
    ↓
RabbitListener: voucherOrderHandler
   - 最多重试 3 次（x-retry-count header）
   - DB 乐观锁扣减（stock=stock-1 WHERE stock=stock_old）
   - 成功 -> basicAck
   - 失败 -> 重试 -> 超 3 次 -> 死信队列
```

### 4.3 多级缓存架构

```
请求 -> RefreshTokenInterceptor
    ↓
Caffeine 用户信息缓存 (cache:userinfo:{id})
   - TTL: 20min, 异步刷新: 10min, 最大 10000
   - 未命中 -> 返回空值 + 触发 BatchLoadCache
    ↓
Caffeine Token版本缓存 (cache:token:version:{id})
   - TTL: 5min, 最大 10000
   - 命中+匹配 -> 放行
   - 命中+不匹配 -> 401
   - 未命中 -> 走 Redis
    ↓
Redis
   - login:token:access:{id} / login:token:refresh:{id}
   - token:version:valid:{id}
   - cache:userinfo:{id} (Hash)
    ↓
MySQL (tb_user / tb_user_info)
```

**BatchLoadCache 机制**：定时任务每 2s 轮询 writingUserIds -> pipeline 批量查 Redis -> 未命中者批量查 MySQL -> pipeline 批量回写 Redis -> 更新 Caffeine

---

## 五、Redis Key 设计

| Key 模式 | 类型 | TTL | 用途 |
|----------|------|-----|------|
| login:code:{phone} | String | 2min | 验证码 |
| login:token:access:{userId} | String | 30min | accessToken |
| login:token:refresh:{userId} | String | 7天 | refreshToken |
| token:version:valid:{userId} | String | 7天 | 当前有效版本号 |
| token:version:current:{userId} | String | 8天 | 版本号自增计数器 |
| cache:userinfo:{userId} | Hash | 20min | 用户缓存信息 |
| seckill:stock:{voucherId} | String | — | 秒杀库存 |
| seckill:order:{voucherId} | Set | — | 已下单用户集合 |
| seckill:order:info:{orderId} | Hash | 24h | 订单信息 |
| login:fail:ip:{ip} | String | 60s | IP 登录频率限制 |
| login:fail:count:{userId} | String | 24h | 密码错误次数 |
| login:lock:{userId} | String | 30min | 账户锁定 |
| lock:refresh:{userId} | String | 3s | Token 刷新分布式锁 |
| sign:{userId}:{yyyyMM} | BitMap | — | 签到记录 |
| blog:liked:{blogId} | Set | — | 博客点赞用户 |
| feed:{userId} | ZSet | — | 关注推送收件箱 |
| shop:geo:{typeId} | GEO | — | 店铺地理位置 |
| cache:shop:{id} | String | — | 店铺缓存 |
| cache:shopType | String | — | 店铺类型缓存 |
| lock:shop:{id} | String | 10s | 店铺缓存重建互斥锁 |
| icr:order:{yyyy:MM:dd} | String | — | 订单 ID 自增计数器 |
| login:userinfo:{userId} | String | — | 用户信息缓存（已废弃） |

---

## 六、Lua 脚本清单

| 脚本文件 | 用途 | 关键返回值 |
|----------|------|-----------|
| `LoginSetToken.lua` | 登录时原子写入 4 个 Redis Key | 状态码 |
| `RefreshToken.lua` | 临期刷新：校验 refreshToken + version -> 更新 accessToken | 200/40x/41x/42x |
| `RefreshExpiredToken.lua` | 过期刷新：bump version -> 更新完整 TokenPair | 200/40x/41x/42x |
| `MqSeckill.lua` | 秒杀：校验库存/去重 -> DECR -> SADD -> HMSET | 200/50x/51x |
| `ConsumeVerifyCode.lua` | 原子消费验证码（GET + DEL） | String |
| `RedisUnlock.lua` | 分布式锁释放（比较+删除） | — |

---

## 七、RabbitMQ 架构

```
生产者 -> Normal Exchange (topic)
           | alternate-exchange -> Alternate Exchange (fanout)
           |
           Queue: voucher_order
           | x-dead-letter-exchange -> Dead Exchange (topic)
           | x-message-ttl: 60000
           | x-max-length: 100000
           | x-queue-type: classic
           |
           消费者 -> 手动 ACK
           | 重试 3 次（x-retry-count header）
           | 超限 -> Dead Queue
           |
           Dead Queue: dead_voucher_order
           | x-message-ttl: 60000
           | x-max-length: 10000
           | x-delivery-limit: 20
           | x-overflow: drop-head
```

---

## 八、性能优化要点

| 问题 | 方案 | 效果 |
|------|------|------|
| 单 Token 不支持续期 | 双 Token（access 30min + refresh 7天）+ 版本号 | 支持自动续期，防重放 |
| Token 刷新并发覆盖 | Redis Lua 原子操作 + 分布式锁 | 同一用户只有一个刷新请求 |
| Token 校验频繁查 Redis | Caffeine 两级缓存（版本号+用户信息） | 减少 Redis 网络开销 |
| 数据库库存扣减慢 | Redis 预扣库存 + RabbitMQ 异步落库 | 快速响应，秒杀 QPS 提升 |
| 频繁查询用户信息 | Caffeine + Redis Hash + 异步批量加载 | 减少数据库压力 |
| 同步日志导致 Full GC | 异步 Appender（队列大小 1024） | Full GC 降为 0 |
| Redis 连接池过小 | max-active 从 6 调到 100-200 | 等待时间减少，QPS 更稳定 |
| 冷启动慢 | Lua 脚本预加载 + 连接池预热 | 第一次请求不再慢 |
| 网络 IO 延迟 | Caffeine 本地缓存 | 减少网络开销 |
| ID 生成延迟 | 雪花算法 + 预生成队列（10000 个/批） | 避免高并发 ID 生成瓶颈 |

---

## 九、关键类关系简图

```
UserController -> UserServiceImpl -> AuthService (generateTokenPair/validate/refresh)
                   ↑                     ↑
              IUserService           AuthServiceImpl（P2 已拆：TokenService/VerifyCodeService/PasswordService）
                   ↑                     ↑
              UserMapper           JwtUtil + RedisIdWorker + Lua Scripts

VoucherOrderController -> MqVoucherOrderServiceImpl (@Primary)
                            ├─ MqSeckill.lua (Redis 原子校验+扣减)
                            ├─ VoucherOrder (MySQL 落库)
                            └─ RabbitTemplate -> RabbitMQ (异步扣减)

RefreshTokenInterceptor -> AuthService
    ├─ JwtUtil (解析 claims)
    ├─ Caffeine (userinfoCache + tokenValidVersionCache)
    ├─ Redis (StringRedisTemplate)
    └─ CookieConfig

BatchLoadCache -> @Scheduled(2s) 轮询
    ├─ pipeline 批量读 Redis
    ├─ 未命中 -> 批量查 MySQL
    └─ pipeline 批量写回 Redis -> 更新 Caffeine
```

---

> 文档由 AI 自动生成，基于源码分析