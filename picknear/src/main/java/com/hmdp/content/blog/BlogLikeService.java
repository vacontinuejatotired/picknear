package com.hmdp.content.blog;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.hmdp.common.lock.LockTemplate;
import com.hmdp.content.entity.Blog;
import com.hmdp.content.mapper.BlogMapper;
import com.hmdp.dto.Result;
import com.hmdp.utils.UserHolder;
import com.hmdp.utils.redis.RedisConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.util.concurrent.TimeUnit;

/**
 * 博客点赞服务 — 点赞/取消（content 域收敛，P3-S2）
 * <p>
 * 自 BlogServiceImpl.likeBlog 迁出（行为等价）：Redis Set（用户维度）+ ZSet（TopN）
 * 双写 + DB liked 计数 + 博客缓存同步刷新。锁经 {@link LockTemplate}（P4-S1 修复 H-3）。
 * </p>
 */
@Slf4j
@Component
public class BlogLikeService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private BlogMapper blogMapper;
    @Resource
    private LockTemplate lockTemplate;

    /** 点赞/取消点赞（防并发重复由 Redis 锁保证） */
    public Result likeBlog(Long id) {
        Long userId = UserHolder.getUserId();
        String zsetKey = RedisConstants.BLOG_LIKED_KEY + id;
        String userKey = RedisConstants.USER_LIKED_KEY + userId;
        String lockKey = "lock:like:" + id + ":" + userId;

        // 分布式锁，防并发重复点赞/取消
        try (LockTemplate.LockHandle lock = lockTemplate.tryLock(lockKey, 3, TimeUnit.SECONDS)) {
            if (lock == null) {
                return Result.fail("操作太频繁，请稍后再试");
            }
            // 优先查 Set（用户维度），ZSet 只用于 TopN 查询
            Boolean isLiked = stringRedisTemplate.opsForSet().isMember(userKey, String.valueOf(id));
            if (Boolean.FALSE.equals(isLiked)) {
                int updated = blogMapper.update(null, new UpdateWrapper<Blog>()
                        .setSql("liked = liked + 1").eq("id", id));
                if (updated > 0) {
                    stringRedisTemplate.opsForSet().add(userKey, String.valueOf(id));
                    stringRedisTemplate.opsForZSet().add(zsetKey, userId.toString(), System.currentTimeMillis());
                }
            } else {
                int updated = blogMapper.update(null, new UpdateWrapper<Blog>()
                        .setSql("liked = liked - 1").eq("id", id));
                if (updated > 0) {
                    stringRedisTemplate.opsForSet().remove(userKey, String.valueOf(id));
                    stringRedisTemplate.opsForZSet().remove(zsetKey, userId.toString());
                }
            }
            // 同步刷新博客缓存中的 liked 数
            Blog blog = blogMapper.selectById(id);
            if (blog != null) {
                String cacheKey = RedisConstants.CACHE_BLOG_KEY + id;
                long ttl = RedisConstants.CACHE_BLOG_TTL + (long) (Math.random() * RedisConstants.CACHE_BLOG_TTL);
                stringRedisTemplate.opsForValue().set(cacheKey, JSONUtil.toJsonStr(blog), ttl, TimeUnit.MINUTES);
            }
        }
        return Result.ok();
    }
}
