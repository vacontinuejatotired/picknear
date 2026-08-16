package com.hmdp.content.feed;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hmdp.content.entity.Blog;
import com.hmdp.content.entity.Follow;
import com.hmdp.content.mapper.BlogMapper;
import com.hmdp.content.mapper.FollowMapper;
import com.hmdp.utils.redis.RedisConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Feed 推送服务 — 推模式写侧（content 域收敛，P3-S1）
 * <p>
 * 职责（自 BlogServiceImpl.pushBloToFansBatch / FollowServiceImpl.backfillFeedOnFollow 迁出，行为等价）：
 * <ul>
 *   <li>pushToFans：作者发布博客后，向全部粉丝的 feed ZSet 推送（pipeline 批量）</li>
 *   <li>backfillOnFollow：关注成功后回填被关注者历史博客（最多 20 篇）</li>
 * </ul>
 * 粉丝/博客查询经 {@link FollowMapper}/{@link BlogMapper} 直查——
 * 不再依赖 IFollowService/IBlogService，是解除 Blog↔Follow 循环依赖的单向中转。
 * </p>
 */
@Slf4j
@Component
public class FeedPushService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private FollowMapper followMapper;
    @Resource
    private BlogMapper blogMapper;

    /**
     * 推送给某作者的全部粉丝（pipeline 批量 ZAdd，防止 N 次连接）
     *
     * @param authorUserId 作者用户 ID
     * @param blogId       新博客 ID
     */
    public void pushToFans(Long authorUserId, Long blogId) {
        List<Follow> follows = followMapper.selectList(
                new LambdaQueryWrapper<Follow>().eq(Follow::getFollowUserId, authorUserId));
        if (follows == null || follows.isEmpty()) {
            log.info("Empty follows");
            return;
        }
        log.info("开始批量插入");
        stringRedisTemplate.executePipelined((RedisCallback<Object>) connect -> {
            double now = System.currentTimeMillis();
            byte[] valueByte = blogId.toString().getBytes(StandardCharsets.UTF_8);
            for (Follow follow : follows) {
                String key = "feed:" + follow.getUserId();
                connect.zAdd(key.getBytes(StandardCharsets.UTF_8), now, valueByte);
            }
            return null;
        });
    }

    /**
     * 关注成功后回填被关注者的历史博客到关注者的 feed 流
     * <p>
     * 推模式（push mode）下，只有新发布的博客才会推送给粉丝。
     * 当用户新关注一个已有博客的账号时，需要把该账号的历史博客回填，
     * 否则关注者的 feed 流中永远不会出现该账号的旧博客。
     */
    public void backfillOnFollow(Long followerUserId, Long followedUserId) {
        // 查询被关注者已发布的博客（有图片的），按 id 降序取前 20 篇
        List<Blog> recentBlogs = blogMapper.selectList(new LambdaQueryWrapper<Blog>()
                .eq(Blog::getUserId, followedUserId)
                .ne(Blog::getImages, "")
                .orderByDesc(Blog::getId)
                .last("LIMIT 20"));
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
