package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.ITestService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.TokenTestUtil;
import com.hmdp.utils.redis.RedisConstants;
import com.hmdp.utils.redis.RedisIdWorker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.ReturnType;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.time.LocalDateTime;

/**
 * 测试工具服务实现 — 秒杀库存重置、批量生成Token、雪花ID校验
 */
@Service
@Slf4j
public class TestServiceimpl implements ITestService {
    @Resource
    private IVoucherOrderService voucherOrderService;

    @Resource
    private TokenTestUtil tokenTestUtil;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;


    @Override
    public Result restart(Long num,Long voucherId) {
        stringRedisTemplate.delete(RedisConstants.SECKILL_ORDERIFNO_KEY+voucherId);
        stringRedisTemplate.delete(RedisConstants.SECKILL_ORDER_EXIST_USER_ZSET_KEY+voucherId);
        stringRedisTemplate.opsForValue().set(RedisConstants.SECKILL_STOCK_KEY+voucherId,String.valueOf(num));
        Long l = deleteByPattern(RedisConstants.SECKILL_ORDERIFNO_KEY + "*");
        System.out.println("删除 "+l +" 条");
        voucherOrderService.deleteVoucherOrders(voucherId,num);
        return Result.ok("删除成功");
    }

    public Long deleteByPattern(String pattern) {
        // 返回删除了多少个key
        String luaScript =
                "local keys = redis.call('keys', KEYS[1]) " +
                        "local count = 0 " +
                        "for _, key in ipairs(keys) do " +
                        "    redis.call('del', key) " +
                        "    count = count + 1 " +
                        "end " +
                        "return count";  // 返回删除数量

        return stringRedisTemplate.execute(
                (RedisCallback<Long>) connection ->
                        (Long) connection.eval(luaScript.getBytes(),
                                ReturnType.INTEGER,
                                1,
                                pattern.getBytes())
        );
    }
    @Override
    public Result generateTestToken(Long num,String fileName) {
        tokenTestUtil.exportTokenAndRefreshTokenToCsv(Math.toIntExact(num),fileName);
        return Result.ok("success");
    }

    @Override
    public Result checkSnowFlake(int num) {
        redisIdWorker.showSnowflakeIdQueueInfo(num);
        log.info("取出的ID数量: {}", num);
        return Result.ok();
    }


    /**
     * 用于压测mysql的测试方法
     * 模拟生成订单的过程，实际业务中会有更多的逻辑，这里仅仅是为了测试数据库的性能
     * 这里不做扣减redis的库存等操作，直接生成订单数据插入数据库
     * @param voucherId
     * @param orderNum
     * @return
     */
    @Override
    public Result testSaveOrder(Long voucherId, Long orderNum) {
        VoucherOrder voucherOrder = new VoucherOrder().setId(redisIdWorker.getIdFromQueue()).setVoucherId(voucherId)
                .setStatus(1).setUserId(123L).setCreateTime(LocalDateTime.now());
        long startTime = System.nanoTime();
        for(int i=0 ; i<orderNum; i++){
            voucherOrderService.sendMqMessage(voucherOrder);
        }
        long endTime = System.nanoTime();
        log.info("发送 {} 条订单消息耗时: {} ms", orderNum, (endTime - startTime) / 1_000_000);
        return Result.ok();
    }
}
