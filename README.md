# picknear - 高并发点评平台（黑马点评扩展版）

基于黑马程序员 hm-dianping 项目进行二次开发与高并发优化，重点改进用户认证登录流程和高并发订单/库存扣减逻辑。

## 技术栈

- 后端：SpringBoot、MyBatis-Plus
- 数据库：MySQL、Redis
- 缓存：Caffeine（本地缓存）、Redis（Lua 脚本、Hash、分布式锁）
- 消息队列：RabbitMQ（Confirm 机制、幂等消费）
- 文件存储：本地文件 / 阿里云 OSS（双实现，通过 @Profile 切换）
- 工具：Maven、Git、JMeter（压测）、Docker、Arthas

## 用户认证与安全优化

项目重构了原有的单Token机制，引入双Token（Access Token + Refresh Token）以支持自动续期，并通过版本号机制防止过期Token滥用。针对高并发场景，使用Redis + Lua脚本实现Token刷新原子操作，避免并发覆盖。

- **双Token机制**：原单Token不支持续期，易受重放攻击，引入Access Token（30分钟）和Refresh Token（7天），每次刷新生成新Refresh Token，旧Token一次性使用。
- **版本号控制**：Token过期后可能被组合使用，添加version字段，严格校验Token组合有效性。
- **原子刷新**：高并发下Token刷新可能冲突，使用Redis Lua脚本原子执行检查、删除旧Token、写入新Token。
- **单设备登录**：多设备登录导致Token混乱，Redis存储用户当前有效Token，新登录失效旧Token。
- **缓存优化**：频繁查询用户信息影响性能，采用Caffeine本地缓存 + Redis Hash + 异步批量加载，减少数据库压力。
- **Token版本本地缓存**：每次请求都查Redis校验版本号增加网络开销，采用Caffeine本地缓存快速拒绝 + Redis最终校验的两级策略。本地缓存命中但版本不匹配时直接拒绝（token一定无效），命中且版本匹配或未命中时走Redis做最终校验，校验通过后同步更新本地缓存。

[查看登录流程](md/login-process-flow.md)  
[查看Refresh拦截器流程](md/refresh-token-interceptor-flow.md)  
[查看压测报告](md/下单优化压测报告.md)  
[查看刷新过期Token流程](md/refresh-expired-token-flow.md)

- **密码登录**：在原有验证码登录基础上增加了密码登录，使用 BCrypt 加密存储，含账户锁定（连续 10 次失败锁 30 分钟）和失败频率限制。

## 文件上传优化

接入阿里云 OSS 图片存储，通过 `FileService` 接口统一上传逻辑，dev 环境走本地文件，prod 环境走 OSS。

- **FileService 接口**：定义 upload/delete/getDomain，通过 `@Profile` 切换实现
- **文件校验**：白名单 jpg/png/gif/webp，大小限制 5MB
- **Object Key 散列**：`{module}/{d1}/{d2}/{uuid}.{ext}` 格式，避免 OSS 单目录超限

## 高并发库存扣减优化

针对高并发下单场景，原直接扣减数据库易导致锁竞争和延迟，采用Redis预减库存 + 异步消息落库方案。库存扣减逻辑使用Lua脚本原子执行，消息通过RabbitMQ可靠投递。

- **Redis预减库存**：下单时先在Redis扣减库存，快速响应请求，缓解数据库压力。
- **Lua原子操作**：库存检查、扣减、重复校验非原子易超卖，单脚本内完成这些操作。
- **异步落库**：同步扣减影响响应速度，RabbitMQ异步投递扣减消息，业务与数据库解耦。
- **消息可靠投递**：消息丢失可能导致扣减失败，采用Confirm + Return机制，支持幂等消费。

## 高并发问题排查与优化

在高并发测试中，发现并解决了以下问题：

- **Lua脚本锁竞争**：329个线程BLOCKED在DefaultRedisScript.getSha1()，原因是getSha1()用synchronized修饰，每次调用拿锁，解决方法继承DefaultRedisScript，重写getSha1()预计算SHA1，避免懒加载，效果BLOCKED线程数量显著减少，QPS有所提升。
- **同步日志导致老年代爆满**：5分钟输出700MB日志，老年代使用率97%，Full GC 472次，原因是同步日志模式下日志对象堆积，解决改成异步日志AsyncAppender，队列大小2048，效果在压测情况下5分钟内Full GC次数降为0，内存使用率显著降低。
- **Redis连接池过小**：800 Tomcat线程配16 Redis连接导致等待，解决max-active从16调到100-200，效果等待时间减少，QPS更稳定。
- **冷启动慢**：重启后第一次请求慢，原因是连接池未初始化、JIT未编译、脚本懒加载，解决脚本预热。
- **网络IO延迟**：网络IO占35ms左右，非本机部署必然，解决添加Caffeine本地缓存，基于ConcurrentHashMap实现，减少对Redis访问。
- **用户信息更新同步阻塞**：原有逻辑在拦截器中同步更新用户信息，每次请求从Redis获取，网络延迟大，解决添加定时任务批量异步更新用户信息。

## 其他优化

- **多级缓存架构**：网络开销大时影响性能，本地Caffeine缓存 + Redis + 数据库逐级降级。
- **批量缓存更新**：频繁小量更新低效，定时批量刷新，先查Redis筛除已有，再查MySQL，区分有效和空值用户。
- **分布式ID生成**：实时生成ID延迟高，使用Redis雪花算法 + 预生成队列，避免延迟。
- **点赞异步处理**：实时写数据库慢，Redis缓存，定时批量写入。

## 个人主要贡献

作为大二学生，主要独立完成了以下内容：
- 重构用户认证模块：从单Token改为双Token，添加版本号机制和原子刷新逻辑。
- 实现库存扣减优化：Redis预减 + Lua原子操作 + RabbitMQ异步落库。
- 设计多级缓存系统：Caffeine本地缓存 + BatchLoadCache异步批量加载。
- 编写压测脚本：使用JMeter和wrk测试空接口性能。

## 运行与压测说明

1. 配置 application.yml 中的 Redis、MySQL、RabbitMQ 连接信息。
2. 启动项目：`mvn spring-boot:run` 或 IDE 直接运行。
3. 压测脚本位于项目根目录下的 JMeter 文件（.jmx）。
4. ReadMe是Ai写的，运行这里有问题可以来问我，或者直接看代码，代码里有注释。

项目依赖Redis和RabbitMQ，可使用Docker快速部署：
- Redis：`docker run -d -p 6379:6379 redis:alpine`
- RabbitMQ：`docker run -d -p 5672:5672 -p 15672:15672 rabbitmq:management-alpine`

Docker部署Redis/RabbitMQ + 本机Spring Boot 空接口压测结果：JMeter 5500 TPS、wrk 16000 TPS。

## 关键文件位置

- 刷新临期token Lua 脚本：`src/main/resources/refreshToken.lua`（原子刷新Token）
- 刷新过期token Lua 脚本：`src/main/resources/refreshExpiredToken.lua`（处理过期Token续期）
- 库存扣减 Lua 脚本：`src/main/resources/MqSeckill.lua`（库存检查、扣减、重复校验）
- 拦截器与令牌逻辑：`src/main/java/com/hmdp/interceptor/RefreshTokenInterceptor.java`（Token验证和刷新，委托 AuthService）
- 认证服务：`src/main/java/com/hmdp/service/AuthService.java`（Token 生成/校验/刷新）
- 批量缓存加载工具：`src/main/java/com/hmdp/utils/cache/BatchLoadCache.java`（异步批量加载用户缓存）
- 分布式ID生成工具：`src/main/java/com/hmdp/utils/redis/RedisIdWorker.java`（雪花算法ID生成 + 预生成队列）
- Caffeine缓存配置：`src/main/java/com/hmdp/config/CacheConfig.java`（本地缓存配置）
- 文件上传接口：`src/main/java/com/hmdp/service/FileService.java`（本地/OSS 双实现）
- 测试脚本: '空接口耗时.jmx'（JMeter测试脚本）
最后更新：2026 年 7 月
