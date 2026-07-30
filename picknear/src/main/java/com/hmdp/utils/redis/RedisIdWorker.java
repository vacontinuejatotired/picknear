package com.hmdp.utils.redis;

import cn.hutool.core.util.RandomUtil;
import com.esotericsoftware.minlog.Log;
import com.hmdp.entity.SnowflakeIdQueue;
import com.hmdp.utils.UserHolder;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * Redis 分布式 ID 生成器 — 雪花算法 + ID预生成队列，避免高并发ID生成延迟
 */
@Component
@Slf4j
public class RedisIdWorker {
    private static final Long BEGIN_TIME = 1735689600L;

    private static final Long BEGIN_TIME_MS= 1735689600000L;
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    private static final SnowflakeIdQueue SNOW_FLAKE_ID_QUEUE = new SnowflakeIdQueue();
    /**
     * 序列号位数
     */
    private static final int COUNT_BITS = 32;

    @PostConstruct
    public void init() throws InterruptedException {
        log.info("正在初始化ID生成器...");
        batchGenerateId();
    }

    /**
     * 根据订单Id使用情况动态获取新Id，避免一次性生成过多Id导致内存占用过高
     */
    public void batchGenerateId() {
        log.info("准备批量生成ID");

        String today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        String counterKey = "icr:order:" + today;
        byte[] keyBytes = counterKey.getBytes(StandardCharsets.UTF_8);

        int batchSize = 10000;

        // 批量获取自增值
        List<Object> results = stringRedisTemplate.executePipelined(
                (RedisCallback<Long>) connection -> {
                    for (int i = 0; i < batchSize; i++) {
                        connection.incr(keyBytes);
                    }
                    return null;
                }
        );

        // 生成 ID 并入队，每个 ID 使用独立的时间戳
        for (Object obj : results) {
            Long incr = (Long) obj;
//            long timestamp = LocalDateTime.now().toEpochSecond(ZoneOffset.UTC) - BEGIN_TIME;
            long timestamp = System.currentTimeMillis() - BEGIN_TIME_MS;
            long id = (timestamp << COUNT_BITS) | incr;
            try {
                SNOW_FLAKE_ID_QUEUE.put(id);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("批量生成ID时线程被中断", e);
            }
        }

        log.info("批量生成ID完成，当前队列大小: {}", SNOW_FLAKE_ID_QUEUE.size());
    }

    public Long getIdFromQueue()  {
        Long id = SNOW_FLAKE_ID_QUEUE.take();
        if (id == -1L) {
            log.info("ID队列需要刷新，正在批量生成新ID...");
            try {
                batchGenerateId();
            } finally {
                SNOW_FLAKE_ID_QUEUE.finishRefresh();
            }
            id = SNOW_FLAKE_ID_QUEUE.take();
        }
        return id;
    }

    public void showSnowflakeIdQueueInfo(int num) {
        log.info("当前ID队列元素个数: {}", SNOW_FLAKE_ID_QUEUE.size());
        log.info("当前ID队列是否正在刷新: {}", SNOW_FLAKE_ID_QUEUE.checkRefresh());
        log.info("指定容量{}", SNOW_FLAKE_ID_QUEUE.getInitCapacity());
        for(int i = 0; i < num; i++) {
            log.info("获取ID: {}", getIdFromQueue());
        }
    }

    public RedisIdWorker(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public long nextId(String keyPrefix) {
        LocalDateTime now = LocalDateTime.now();
        long timeStamp = now.toEpochSecond(ZoneOffset.UTC) - BEGIN_TIME;
        String date = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        Long count = stringRedisTemplate.opsForValue().increment("icr:" + keyPrefix + ":" + date);
        return timeStamp << COUNT_BITS | count;
    }


    /**
     * 本方法只可在ThreadLocal置上下文的线程上使用
     *
     * @return
     */
    public Long nextVersion() {
        return nextVersion(UserHolder.getUserId());
    }

    /**
     * 通用方法，ThreadLocal未设置上下文也可使用
     *
     * @param userId
     * @return
     */
    public Long nextVersion(Long userId) {
        if (userId == null) {
            Log.info("current userId is null");
            return -1L;
        }
        String key = RedisConstants.CURRENT_TOKEN_VERSION_KEY + userId;
        Long count = stringRedisTemplate.opsForValue().increment(key);
// 如果是第一次increment（返回1），说明key是新建的，设置过期时间
        if (count == 1) {
            stringRedisTemplate.expire(key, 8, TimeUnit.DAYS);
            log.debug("Set initial expire for new version key: {}", key);
        }
        return count;
    }
}
