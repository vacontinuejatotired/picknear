package com.hmdp.voucher.stock;

import com.hmdp.voucher.service.ISeckillVoucherService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 秒杀库存服务 — Redis+Lua 原子扣减与回补
 * <p>
 * 无状态、无 MQ 依赖：只负责 Redis 侧库存操作与 DB 侧库存回补。
 * Lua 原子性保证"库存校验 + 重复下单校验 + 扣减"单次完成。
 * </p>
 * <p>
 * 返回码语义沿用 Lua 脚本原始约定（MqSeckill.lua）：
 * 0=成功扣减；1=库存不足；2=重复下单。⚠️ 与 {@code SeckillOrderCode}
 * 枚举（200/501/511）不一致，属既有设计，本组件保持等价透传。
 * </p>
 */
@Slf4j
@Component
public class SeckillStockService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource(name = "seckillScript")
    private DefaultRedisScript<Long> seckillScript;

    /** Lua 返回码：成功 */
    public static final long RESULT_SUCCESS = 0L;
    /** Lua 返回码：库存不足 */
    public static final long RESULT_INSUFFICIENT_STOCK = 1L;
    /** Lua 返回码：重复下单 */
    public static final long RESULT_REPEAT_ORDER = 2L;

    /**
     * 执行秒杀 Lua 脚本：原子扣减 Redis 库存并校验重复下单
     *
     * @return Lua 原始返回码（0/1/2，见类注释）
     */
    public Long deductStock(Long voucherId, Long userId, Long orderId) {
        long startTime = System.currentTimeMillis();
        List<String> args = Arrays.asList(
                String.valueOf(voucherId),
                String.valueOf(userId),
                String.valueOf(orderId)
        );
        try {
            Long result = stringRedisTemplate.execute(
                    seckillScript,
                    Collections.emptyList(),
                    args.toArray()
            );
            long costTime = System.currentTimeMillis() - startTime;
            // Lua脚本执行耗时告警
            if (costTime > 200) {
                log.warn("【性能告警】Lua脚本执行过慢: {} ms", costTime);
            }
            return result != null ? result : -1L;
        } catch (Exception e) {
            log.error("【deductStock】执行异常", e);
            throw new RuntimeException("Lua脚本执行失败", e);
        }
    }

    /**
     * 回补 MySQL 库存（测试/运维用）
     *
     * @param voucherId 优惠券 ID
     * @param stock     回补后的库存值
     */
    public void restoreDbStock(Long voucherId, Long stock) {
        seckillVoucherService.update()
                .eq("voucher_id", voucherId)
                .set("stock", stock)
                .update();
    }
}
