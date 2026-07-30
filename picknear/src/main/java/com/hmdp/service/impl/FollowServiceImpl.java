package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IBlogService;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import com.hmdp.utils.redis.RedisConstants;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 关注服务实现 — 关注/取关（Redis Set存储关注列表）、共同关注（Set交集运算）
 */
@Service
@Slf4j
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IUserService userService;

    @Resource
    @Lazy  // 避免与 BlogServiceImpl → IFollowService 形成循环依赖
    private IBlogService blogService;

    // 最大重试次数
    private static final int MAX_RETRY_TIMES = 3;

    @Override
    public Result queryFollowStatus(Long targetUserId) {
        return queryFollowStatusWithRetry(targetUserId, 0);
    }

    private Result queryFollowStatusWithRetry(Long targetUserId, int retryCount) {
        Long userId = UserHolder.getUserId();

        // 1. 先查 user:exists 缓存
        String userExistsKey = RedisConstants.USER_EXISTS_KEY + targetUserId;
        String existsStr = stringRedisTemplate.opsForValue().get(userExistsKey);
        if ("0".equals(existsStr)) {
            return Result.ok(false);
        }

        // 2. 查 follows:targetId 集合（目标用户的粉丝列表）
        String followersKey = RedisConstants.FOLLOWERS_KEY + targetUserId;
        Boolean isMember = stringRedisTemplate.opsForSet().isMember(followersKey, userId.toString());

        // 3. 如果集合存在，直接返回结果
        if (Boolean.TRUE.equals(isMember)) {
            return Result.ok(true);
        }

        // 4. 检查集合是否存在
        Boolean hasKey = stringRedisTemplate.hasKey(followersKey);
        if (Boolean.TRUE.equals(hasKey)) {
            return Result.ok(false);
        }

        // 5. 检查 follows:empty 标记
        String emptyKey = RedisConstants.FOLLOWS_EMPTY_KEY + targetUserId;
        Boolean hasEmptyKey = stringRedisTemplate.hasKey(emptyKey);
        if (Boolean.TRUE.equals(hasEmptyKey)) {
            return Result.ok(false);
        }

        // 6. 检查重试次数
        if (retryCount >= MAX_RETRY_TIMES) {
            // 重试次数过多，直接查数据库
            return queryFromDatabase(userId, targetUserId);
        }

        // 7. 尝试加锁
        String lockKey = RedisConstants.LOCK_FOLLOW_KEY + targetUserId;
        Boolean lockAcquired = stringRedisTemplate.opsForValue().setIfAbsent(lockKey, "1", RedisConstants.LOCK_FOLLOW_TTL, TimeUnit.SECONDS);

        if (!Boolean.TRUE.equals(lockAcquired)) {
            // 加锁失败，等待后重试
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return queryFollowStatusWithRetry(targetUserId, retryCount + 1);
        }

        try {
            // 8. 双重检查
            isMember = stringRedisTemplate.opsForSet().isMember(followersKey, userId.toString());
            if (Boolean.TRUE.equals(isMember)) {
                return Result.ok(true);
            }
            hasKey = stringRedisTemplate.hasKey(followersKey);
            if (Boolean.TRUE.equals(hasKey)) {
                return Result.ok(false);
            }
            hasEmptyKey = stringRedisTemplate.hasKey(emptyKey);
            if (Boolean.TRUE.equals(hasEmptyKey)) {
                return Result.ok(false);
            }

            // 9. 查询数据库 - 只查单条记录，不拉整个列表
            // 9.1 检查目标用户是否存在
            boolean targetUserExists = userService.getById(targetUserId) != null;
            if (!targetUserExists) {
                stringRedisTemplate.opsForValue().set(userExistsKey, "0", RedisConstants.USER_EXISTS_TTL, TimeUnit.SECONDS);
                return Result.ok(false);
            }

            // 9.2 只查当前用户是否关注了目标用户
            Follow follow = query()
                    .eq("user_id", userId)
                    .eq("follow_user_id", targetUserId)
                    .one();

            // 9.3 写入单个用户到缓存，而不是整个列表
            if (follow != null) {
                stringRedisTemplate.opsForSet().add(followersKey, userId.toString());
                stringRedisTemplate.expire(followersKey, RedisConstants.FOLLOWS_TTL, TimeUnit.SECONDS);
                return Result.ok(true);
            } else {
                // 没有关注，写入空标记
                stringRedisTemplate.opsForValue().set(emptyKey, "1", RedisConstants.FOLLOWS_EMPTY_TTL, TimeUnit.SECONDS);
                return Result.ok(false);
            }

        } finally {
            // 10. 释放锁
            stringRedisTemplate.delete(lockKey);
        }
    }

    /**
     * 从数据库查询并重建缓存
     */
    private Result queryFromDatabase(Long userId, Long targetUserId) {
        String userExistsKey = RedisConstants.USER_EXISTS_KEY + targetUserId;
        String followersKey = RedisConstants.FOLLOWERS_KEY + targetUserId;
        String emptyKey = RedisConstants.FOLLOWS_EMPTY_KEY + targetUserId;

        // 检查目标用户是否存在
        boolean targetUserExists = userService.getById(targetUserId) != null;
        if (!targetUserExists) {
            stringRedisTemplate.opsForValue().set(userExistsKey, "0", RedisConstants.USER_EXISTS_TTL, TimeUnit.SECONDS);
            return Result.ok(false);
        }

        // 查询是否关注
        Follow follow = query()
                .eq("user_id", userId)
                .eq("follow_user_id", targetUserId)
                .one();

        boolean isFollowed = follow != null;

        // 重建缓存
        if (isFollowed) {
            // 写入粉丝列表
            stringRedisTemplate.opsForSet().add(followersKey, userId.toString());
            stringRedisTemplate.expire(followersKey, RedisConstants.FOLLOWS_TTL, TimeUnit.SECONDS);
            // 删除空标记
            stringRedisTemplate.delete(emptyKey);
        } else {
            // 写入空标记
            stringRedisTemplate.opsForValue().set(emptyKey, "1", RedisConstants.FOLLOWS_EMPTY_TTL, TimeUnit.SECONDS);
        }

        return Result.ok(isFollowed);
    }

    @Override
    public Result follow(Long targetUserId, Boolean isFollow) {
        Long userId = UserHolder.getUserId();

        if (isFollow) {
            // 关注
            Follow follow = new Follow();
            follow.setUserId(userId);
            follow.setFollowUserId(targetUserId);
            boolean isSuccess = save(follow);
            if (isSuccess) {
                // 更新关注列表：当前用户的关注列表
                String followingKey = RedisConstants.FOLLOWS_KEY + userId;
                stringRedisTemplate.opsForSet().add(followingKey, targetUserId.toString());
                stringRedisTemplate.expire(followingKey, RedisConstants.FOLLOWS_TTL, TimeUnit.SECONDS);

                // 更新粉丝列表：目标用户的粉丝列表
                String followersKey = RedisConstants.FOLLOWERS_KEY + targetUserId;
                stringRedisTemplate.opsForSet().add(followersKey, userId.toString());
                stringRedisTemplate.expire(followersKey, RedisConstants.FOLLOWS_TTL, TimeUnit.SECONDS);

                // 删除空标记
                String emptyKey = RedisConstants.FOLLOWS_EMPTY_KEY + targetUserId;
                stringRedisTemplate.delete(emptyKey);

                // 回填被关注者的历史博客到关注者的 feed 流（最多回填 20 篇）
                backfillFeedOnFollow(userId, targetUserId);
            }
        } else {
            // 取关
            boolean isRemove = remove(new QueryWrapper<Follow>()
                    .eq("user_id", userId)
                    .eq("follow_user_id", targetUserId));
            if (isRemove) {
                // 更新关注列表：当前用户的关注列表
                String followingKey = RedisConstants.FOLLOWS_KEY + userId;
                stringRedisTemplate.opsForSet().remove(followingKey, targetUserId.toString());

                // 更新粉丝列表：目标用户的粉丝列表
                String followersKey = RedisConstants.FOLLOWERS_KEY + targetUserId;
                stringRedisTemplate.opsForSet().remove(followersKey, userId.toString());
            }
        }
        return Result.ok();
    }

    @Override
    public Result queryCommonFollow(Long targetUserId) {
        String targetUserFollowingKey = RedisConstants.FOLLOWS_KEY + targetUserId;
        String currentUserFollowingKey = RedisConstants.FOLLOWS_KEY + UserHolder.getUserId().toString();
        Set<String> intersect = stringRedisTemplate.opsForSet().intersect(targetUserFollowingKey, currentUserFollowingKey);
        if (intersect == null || intersect.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }
        List<Long> commonUserListId = intersect.stream().map(Long::valueOf).toList();
        List<UserDTO> userDTOList = userService.listByIds(commonUserListId)
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .toList();
        return Result.ok(userDTOList);
    }

    /**
     * 关注成功后回填被关注者的历史博客到关注者的 feed 流
     * <p>
     * 推模式（push mode）下，只有新发布的博客才会推送给粉丝。
     * 当用户新关注一个已有博客的账号时，需要把该账号的历史博客回填，
     * 否则关注者的 feed 流中永远不会出现该账号的旧博客。
     */
    private void backfillFeedOnFollow(Long followerUserId, Long followedUserId) {
        // 查询被关注者已发布的博客（有图片的），按 id 降序取前 20 篇
        List<Blog> recentBlogs = blogService.query()
                .eq("user_id", followedUserId)
                .ne("images", "")
                .orderByDesc("id")
                .last("LIMIT 20")
                .list();
        if (recentBlogs == null || recentBlogs.isEmpty()) {
            log.info("回填 feed：被关注用户 {} 没有已发布的博客", followedUserId);
            return;
        }

        String feedKey = RedisConstants.FEED_KEY + followerUserId;
        // 使用博客的创建时间作为 score，逐条写入 ZSet
        for (Blog blog : recentBlogs) {
            double score = (double) blog.getCreateTime()
                    .toInstant(ZoneOffset.UTC).toEpochMilli();
            stringRedisTemplate.opsForZSet().add(
                    feedKey, String.valueOf(blog.getId()), score);
        }
        // 设置过期时间（与关注列表保持一致）
        stringRedisTemplate.expire(feedKey, RedisConstants.FOLLOWS_TTL, TimeUnit.SECONDS);
        log.info("回填 feed 完成：用户 {} 的 {} 篇博客已推送到用户 {} 的 feed",
                followedUserId, recentBlogs.size(), followerUserId);
    }
}
