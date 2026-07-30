-- 参数说明
local voucherId = ARGV[1]  -- 优惠券ID
local userId = ARGV[2]     -- 用户ID
local orderId = ARGV[3]    -- 订单ID

-- Redis Key 定义
local stockKey = 'seckill:stock:' .. voucherId
local orderKey = 'seckill:order:' .. voucherId
local orderInfoKey = 'seckill:order:info:' .. orderId

-- 检查库存是否存在
local stockExist = redis.call('EXISTS', stockKey)
if stockExist == 0 then
    return 502  -- STOCK_NOT_FOUND
end

-- 检查用户是否已下单
local isOrdered = redis.call('SISMEMBER', orderKey, userId)
if isOrdered == 1 then
    return 511  -- REPEAT_ORDER
end

-- 原子扣减库存
local newStock = redis.call('DECR', stockKey)

-- 判断扣减结果
if newStock < 0 then
    -- 扣减失败，恢复库存
    redis.call('INCR', stockKey)
    return 501  -- INSUFFICIENT_STOCK
end

-- 记录用户下单
redis.call('SADD', orderKey, userId)

-- 获取Redis服务器时间
local redisTime = redis.call('TIME')

-- 存储订单信息到Redis
redis.call('HMSET', orderInfoKey,
        'userId', userId,
        'voucherId', voucherId,
        'status', 0,
        'createTime', redisTime[1]
)
redis.call('EXPIRE', orderInfoKey, 86400)  -- 24小时过期

-- 返回成功（如果需要返回数据，可以继续使用JSON，但建议只返回状态码）
-- 如果前端需要订单数据，可以在Java中构建
return 200  -- SUCCESS