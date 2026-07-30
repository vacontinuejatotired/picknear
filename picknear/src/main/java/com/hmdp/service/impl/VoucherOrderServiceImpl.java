//package com.hmdp.service.impl;
//
//import cn.hutool.core.bean.BeanUtil;
//import cn.hutool.core.lang.UUID;
//import com.hmdp.dto.Result;
//import com.hmdp.entity.VoucherOrder;
//import com.hmdp.mapper.VoucherOrderMapper;
//import com.hmdp.service.ISeckillVoucherService;
//import com.hmdp.service.IVoucherOrderService;
//import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
//import com.hmdp.utils.RedisIdWorker;
//import com.hmdp.utils.UserHolder;
//import io.lettuce.core.RedisCommandExecutionException;
//import lombok.extern.slf4j.Slf4j;
//import org.redisson.api.RLock;
//import org.redisson.api.RedissonClient;
//import org.springframework.aop.framework.AopContext;
//import org.springframework.core.io.ClassPathResource;
//import org.springframework.data.redis.connection.stream.*;
//import org.springframework.data.redis.core.StringRedisTemplate;
//import org.springframework.data.redis.core.script.DefaultRedisScript;
//import org.springframework.stereotype.Service;
//import org.springframework.transaction.annotation.Transactional;
//
//import jakarta.annotation.PostConstruct;
//import jakarta.annotation.Resource;
//import java.time.Duration;
//import java.util.Collections;
//import java.util.List;
//import java.util.Map;
//import java.util.concurrent.*;
//import java.util.stream.IntStream;
//
///**
// * <p>
// * 服务实现类
// * </p>
// *
// * @author 虎哥
// * @since 2021-12-22
// */
//@Service
//@Slf4j
//public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {
//    @Resource
//    private VoucherOrderMapper voucherOrderMapper;
//    @Resource
//    private ISeckillVoucherService seckillVoucherService;
//    @Resource
//    private RedisIdWorker redisIdWorker;
//
//
//    @Resource
//    private StringRedisTemplate stringRedisTemplate;
//
//    @Resource
//    private RedissonClient redissonClient;
//    private static final String KEY_PREFIX = "order:";
//    private static final DefaultRedisScript<Long> REDIS_UNLOCK_SCRIPT;
//    private IVoucherOrderService proxy;
//
//    static {
//        REDIS_UNLOCK_SCRIPT = new DefaultRedisScript<>();
//        REDIS_UNLOCK_SCRIPT.setResultType(Long.class);
//        REDIS_UNLOCK_SCRIPT.setLocation(new ClassPathResource("Seckill.lua"));
//    }
//
//    //TODO这里怎么用的是单线程池
//    private static final ExecutorService secKillThreadPool = Executors.newSingleThreadExecutor();
//
//
//    @PostConstruct
//    public void init() {
//        log.info("异步秒杀线程池启动");
//
//        final String streamKey = "stream.orders";
//        final String groupName = "g1";
//        final String lockKey = streamKey + ":group_init_lock";
//        final String lockValue = UUID.randomUUID().toString();
//
//        // 尝试获取分布式锁（60秒过期）
//        Boolean lockAcquired = stringRedisTemplate.opsForValue()
//                .setIfAbsent(lockKey, lockValue, 60, TimeUnit.SECONDS);
//
//        if (Boolean.FALSE.equals(lockAcquired)) {
//            log.info("其他实例正在初始化消费者组，跳过本次初始化");
//            logThreadPoolStatus();
//            return;
//        }
//
//        try {
//            ensureStreamExists(streamKey);
//            ensureConsumerGroupExists(streamKey, groupName);
//            // 只有当前节点成功完成初始化，才启动消费者线程
//            log.info("消费者组初始化完成，启动 VoucherOrderHandler");
//            secKillThreadPool.submit(new VoucherOrderHandler());
//
//        } catch (Exception e) {
//            log.error("Redis Stream 初始化失败 [stream={}, group={}]", streamKey, groupName, e);
//            // 可选：根据业务决定是否需要告警、标记失败状态或重试
//        } finally {
//            releaseLockSafely(lockKey, lockValue);
//        }
//        logThreadPoolStatus();
//    }
//
//    /**
//     * 确保 Stream 存在，如果不存在则创建（使用 dummy entry）
//     */
//    private void ensureStreamExists(String streamKey) {
//        if (Boolean.TRUE.equals(stringRedisTemplate.hasKey(streamKey))) {
//            return;
//        }
//
//        try {
//            stringRedisTemplate.opsForStream()
//                    .add(streamKey, Map.of("__init__", "1"));
//            log.info("创建新的 Redis Stream: {}", streamKey);
//        } catch (Exception e) {
//            log.error("创建 Stream 失败: {}", streamKey, e);
//            throw new IllegalStateException("无法创建 Stream: " + streamKey, e);
//        }
//    }
//
//    /**
//     * 确保消费者组存在（幂等操作）
//     */
//    private void ensureConsumerGroupExists(String streamKey, String groupName) {
//        StreamInfo.XInfoGroups groupsInfo;
//        try {
//            groupsInfo = stringRedisTemplate.opsForStream().groups(streamKey);
//        } catch (RedisCommandExecutionException e) {
//            String msg = e.getMessage();
//            if (msg != null && (msg.contains("NOGROUP") || msg.contains("key does not exist"))) {
//                // Stream 不存在已在前面处理，这里不应该走到
//                log.warn("查询消费者组时 Stream 不存在，已在前面创建: {}", streamKey);
//                return;
//            }
//            throw e;
//        }
//
//        boolean exists = IntStream.range(0, groupsInfo.groupCount())
//                .mapToObj(groupsInfo::get)
//                .anyMatch(g -> groupName.equals(g.groupName()));
//
//        if (exists) {
//            log.info("消费者组已存在，跳过创建: {} / {}", streamKey, groupName);
//            return;
//        }
//
//        try {
//            stringRedisTemplate.opsForStream().createGroup(
//                    streamKey,
//                    ReadOffset.from("0"),
//                    groupName
//            );
//            log.info("成功创建消费者组: {} / {}", streamKey, groupName);
//        } catch (RedisCommandExecutionException e) {
//            if (isGroupAlreadyExistsException(e)) {
//                log.info("消费者组并发创建，已存在: {} / {}", streamKey, groupName);
//            } else {
//                log.error("创建消费者组失败: {} / {}", streamKey, groupName, e);
//                throw e;
//            }
//        }
//    }
//
//    /**
//     * 判断是否为“组已存在”的异常
//     */
//    private boolean isGroupAlreadyExistsException(RedisCommandExecutionException e) {
//        String msg = e.getMessage();
//        return msg != null && (
//                msg.contains("BUSYGROUP") ||
//                        msg.toLowerCase().contains("consumer group name already exists")
//        );
//    }
//
//    /**
//     * 安全释放分布式锁（使用 Lua 脚本校验 value）
//     */
//    private void releaseLockSafely(String lockKey, String lockValue) {
//        try {
//            String luaScript = """
//                if redis.call('get', KEYS[1]) == ARGV[1] then
//                    return redis.call('del', KEYS[1])
//                else
//                    return 0
//                end""";
//
//            Long released = stringRedisTemplate.execute(
//                    new DefaultRedisScript<>(luaScript, Long.class),
//                    Collections.singletonList(lockKey),
//                    lockValue
//            );
//
//            if (released != null && released == 1L) {
//                log.debug("成功释放分布式锁: {}", lockKey);
//            } else {
//                log.debug("锁已过期或被其他实例持有，无需释放: {}", lockKey);
//            }
//        } catch (Exception e) {
//            log.warn("释放分布式锁失败: {}", lockKey, e);
//        }
//    }
//
//    private void logThreadPoolStatus() {
//        log.info("线程池状态: shutdown={}, terminated={}",
//                secKillThreadPool.isShutdown(),
//                secKillThreadPool.isTerminated());
//    }
////     @PostConstruct
////     public void init() {
////         log.info("异步秒杀线程池启动");
////         log.info("开始初始化Redis Stream消费者组...");
////         String streamKey = "stream.orders";
////         String groupName = "g1";
////         String lockKey = streamKey + ":group_init_lock";
////
////         try {
////             // 1. 尝试获取分布式锁（设置30秒过期）
////             Boolean lockAcquired = stringRedisTemplate.opsForValue()
////                     .setIfAbsent(lockKey, "locked", 30, TimeUnit.SECONDS);
////
////             if (Boolean.TRUE.equals(lockAcquired)) {
////                 try {
////                     // 2. 检查流是否存在
////                     if (Boolean.FALSE.equals(stringRedisTemplate.hasKey(streamKey))) {
////                         // 创建空流（等效于MKSTREAM）
////                         Map<String, String> dummyData = Map.of("__init__", "dummy_value");
////                         stringRedisTemplate.opsForStream().add(streamKey, dummyData);
////                         log.info("创建新Stream: {}", streamKey);
////                     }
////
////                     // 3. 尝试创建消费者组
////                     try {
////                         stringRedisTemplate.opsForStream().createGroup(
////                                 streamKey,
////                                 ReadOffset.from("0"),
////                                 groupName
////
////                         );
////                         log.info("成功创建消费者组: {}/{}", streamKey, groupName);
////                     } catch (Exception e) {
////                         //有些异常是灰色的，不能直接log出来，需要拿原因来判断
////                         Throwable rootCause = e.getCause(); // 获取最底层原因
////                         if (rootCause instanceof RedisBusyException) {
////                             log.info("业务提示：{}", rootCause.getMessage()); // 友好提示
////                         } else {
////                             log.error("系统错误", e); // 真实错误仍报警
////                         }
////                     }
////                 } finally {
////                     // 4. 释放锁
////                     try {
////                         //加一个检查其他线程是否持有锁
////                         stringRedisTemplate.delete(lockKey);
////                     } catch (Exception e) {
////                         log.warn("释放锁失败", e);
////                     }
////                 }
////             } else {
////                 log.info("其他节点正在初始化消费者组，跳过初始化");
////             }
////         } catch (Exception e) {
////             log.error("初始化消费者组异常", e);
////         }
////         log.info("线程池状态: shutdown={}, terminated={}",
////                 secKillThreadPool.isShutdown(),
////                 secKillThreadPool.isTerminated());
////         secKillThreadPool.submit(new VoucherOrderHandler());
////     }
//
//    private class VoucherOrderHandler implements Runnable {
//        private final String queueName="stream.orders";
//        @Override
//        public void run() {
//            while (true) {
//                try {
//                    //从阻塞队列拿订单处理
//                    List<MapRecord<String, Object, Object>> read = stringRedisTemplate.opsForStream().read(Consumer.from("g1", "c1")
//                            , StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2))
//                            , StreamOffset.create(queueName, ReadOffset.lastConsumed()));
//                    if (read == null || read.isEmpty()) {
//                        continue;
//                    }
//                    MapRecord<String, Object, Object> redcord = read.get(0);
//                    Map<Object, Object> values = redcord.getValue();
//                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
//                    handleVoucherOrder(voucherOrder);
//                    stringRedisTemplate.opsForStream().acknowledge(queueName,"g1",redcord.getId());
//                } catch (Exception e) {
//                    log.error("处理订单异常", e);
//                    handlePendingList();
//                }
//            }
//        }
//
//        private void handlePendingList() {
//            while (true) {
//                try {
//                    List<MapRecord<String, Object, Object>> pendingList = stringRedisTemplate.opsForStream().read(Consumer.from("g1", "c1"),
//                            StreamReadOptions.empty().count(1),
//                            StreamOffset.create(queueName, ReadOffset.from("0")));
//                    if (pendingList == null || pendingList.isEmpty()) {
//                        //return;
//                        //这里return和break有什么区别
//                        break;
//                    }
//                    MapRecord<String, Object, Object> redcord = pendingList.get(0);
//                    Map<Object, Object> values = redcord.getValue();
//                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
//                    handleVoucherOrder(voucherOrder);
//                    stringRedisTemplate.opsForStream().acknowledge(queueName,"g1",redcord.getId());
//                } catch (Exception e) {
//                    log.info("pendingList订单异常",e);
//                    try {
//                        Thread.sleep(20);
//                    } catch (InterruptedException ex) {
//                        throw new RuntimeException(ex);
//                    }
//                }
//            }
//        }
////        private BlockingQueue<VoucherOrder> orderBlockingQueue = new ArrayBlockingQueue<>(1024 * 1024);
////        @Override
////        public void run() {
////            while (true) {
////                try {
////                    //从阻塞队列拿订单处理
////                    VoucherOrder voucherOrder = orderBlockingQueue.take();
////                    handleVoucherOrder(voucherOrder);
////                } catch (InterruptedException e) {
////                    log.error("订单异常", e);
////                }
////            }
////        }
//    }
//
//    private void handleVoucherOrder(VoucherOrder voucherOrder) {
//        Long userId = voucherOrder.getUserId();
//        RLock simpleRedisLock = redissonClient.getLock(KEY_PREFIX + userId);
//        boolean isLock = simpleRedisLock.tryLock();
//        /**
//         * 能触发失败也就说明同一用户有多个线程
//         */
//        if (!isLock) {
//            log.error("不允许重复下单");
//            return;
//        }
//        //synchronized (userId.toString().intern()) {
//        try {
//            //proxy = (IVoucherOrderService) AopContext.currentProxy();
//            proxy.createVoucherOrder(voucherOrder);
//        } catch (IllegalStateException e) {
//            throw new RuntimeException(e);
//        } finally {
//            simpleRedisLock.unlock();
//        }
//    }
//
//    @Override
//    @Transactional
//    public void createVoucherOrder(VoucherOrder voucherOrder) {
//        Long userId = voucherOrder.getUserId();
//        int count = query().eq("user_id", voucherOrder.getUserId()).eq("voucher_id", voucherOrder.getVoucherId()).count();
//        if (count > 0) {
//            log.error("该用户已经购买过");
//            return;
//        }
//        boolean success = seckillVoucherService.update().setSql("stock = stock -1")
//                .eq("voucher_id", voucherOrder.getVoucherId())
//                //.eq("stock", byId.getStock())
//                .gt("stock", 0)
//                .update();
//        if (!success) {
//            log.error("库存不足");
//            return;
//        }
//        save(voucherOrder);
//        log.info("订单{}插入mysql", voucherOrder.getId());
//    }
//
//    @Override
//    public Result saveOrder(Long voucherId) {
//        return null;
//    }
//
//    public Result querySeckillVoucher(Long voucherId) {
//        Long userId = UserHolder.getUser().getId();
//        Long orderId = redisIdWorker.nextId("order");
//        Long result = stringRedisTemplate.execute(REDIS_UNLOCK_SCRIPT, Collections.emptyList(), voucherId.toString(), userId.toString(),orderId.toString());
//        int r = result.intValue();
//        //0代表才加入缓存，1代表库存不足，2代表重复下单
//        if (r != 0) {
//            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
//        }
//        //警示！！代理对象要变成全局使用的
//        //TODO 集群下目前只会有一个会有代理对象，需要解决
//        proxy = (IVoucherOrderService) AopContext.currentProxy();
//        //返回订单号
//        return Result.ok(orderId);
//    }
////    public Result querySeckillVoucher(Long voucherId) {
////        Long userId = UserHolder.getUser().getId();
////        Long result = stringRedisTemplate.execute(REDIS_UNLOCK_SCRIPT, Collections.emptyList(), voucherId.toString(), userId.toString());
////        int r = result.intValue();
////        //0代表才加入缓存，1代表库存不足，2代表重复下单
////        if (r != 0) {
////            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
////        }
////        Long orderId = redisIdWorker.nexId("order");
////        VoucherOrder voucherOrder = new VoucherOrder();
////        voucherOrder.setVoucherId(voucherId);
////        voucherOrder.setId(orderId);
////        voucherOrder.setUserId(UserHolder.getUser().getId());
////        //插入数据库
////        //放入阻塞队列
////        try {
////            orderBlockingQueue.add(voucherOrder);
////        } catch (Exception e) {
////            log.info("放入订单失败");
////        }
////        //警示！！代理对象要变成全局使用的
////        proxy = (IVoucherOrderService) AopContext.currentProxy();
////        //返回订单号
////        return Result.ok(orderId);
////    }
////    /**
////     * jmeter测试的时候选内容编码一定不要加空格，不然会识别失败
////     * @param voucherId
////     * @return
////     */
////    @Override
////
////    public Result querySeckillVoucher(Long voucherId) {
////        Long userId=UserHolder.getUser().getId();
////        SeckillVoucher byId = seckillVoucherService.getById(voucherId);
////        if(byId.getBeginTime().isAfter(LocalDateTime.now())){
////            return Result.fail("活动未开始");
////        }
////        if(byId.getEndTime().isBefore(LocalDateTime.now())){
////            return Result.fail("活动已结束");
////        }
////        if(byId.getStock() < 1){
////            return Result.fail("库存不足");
////        }
////        /**
////         * 注意事务失效，这里采用找代理解决，上网查一查吧
////         */
////
////        /**
////         * 两个项目启动的话会有两台不同的jvm机，因而锁监视器不共享
////         * 这里采用分布式锁来处理
////         */
//////        SimpleRedisLock simpleRedisLock = new SimpleRedisLock( stringRedisTemplate,KEY_PREFIX + userId);
////        RLock simpleRedisLock = redissonClient.getLock(KEY_PREFIX + userId);
////        boolean isLock = simpleRedisLock.tryLock();
////        /**
////         * 能触发失败也就说明同一用户有多个线程
////         */
////        if(!isLock){
////            return Result.fail("不允许重复下单");
////        }
////        //synchronized (userId.toString().intern()) {
////        try {
////            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
////            return proxy.createVoucherOrder(voucherId);
////        } catch (IllegalStateException e) {
////            throw new RuntimeException(e);
////        } finally {
////            simpleRedisLock.unlock();
////        }
////        //}
////    }
//
//}
