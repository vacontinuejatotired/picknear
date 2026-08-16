package com.hmdp.content.feed;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hmdp.content.entity.Blog;
import com.hmdp.content.entity.Follow;
import com.hmdp.content.mapper.BlogMapper;
import com.hmdp.content.mapper.FollowMapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.user.dto.UserDTO;
import com.hmdp.user.entity.UserInfo;
import com.hmdp.user.service.IUserInfoService;
import com.hmdp.utils.UserHolder;
import com.hmdp.utils.constants.SystemConstants;
import com.hmdp.utils.redis.RedisConstants;
import jodd.util.StringUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Feed 查询服务 — 关注者 Feed 流读侧（content 域收敛，P3-S1）
 * <p>
 * 职责（自 BlogServiceImpl.queryBlogOfFollow/generateFeedForUser 迁出，行为等价）：
 * <ul>
 *   <li>queryBlogOfFollow：ZSet 游标分页（reverseRangeByScoreWithScores） + 懒回填 + 装配</li>
 *   <li>generateFeedForUser：Feed 首次查询且 ZSet 为空时，回填所有关注账号的历史博客</li>
 * </ul>
 * 博客/关注查询经 {@link BlogMapper}/{@link FollowMapper} 直查，不依赖 Service 层。
 * 装配逻辑（setUserToBlog/isLiked）暂内聚于此，P3-S4 收敛至 BlogQueryService。
 * </p>
 */
@Slf4j
@Component
public class FeedQueryService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private BlogMapper blogMapper;
    @Resource
    private FollowMapper followMapper;
    @Resource
    private IUserInfoService userInfoService;

    /** Feed 游标分页查询（最新在前；ZSet 为空触发懒回填） */
    public Result queryBlogOfFollow(Long max, Integer offset) {
        int pageSize = SystemConstants.MAX_PAGE_SIZE;
        Long userId = UserHolder.getUserId();
        String key = RedisConstants.FEED_KEY + userId;
        ScrollResult result = new ScrollResult();
        // 使用 reverseRangeByScoreWithScores 降序返回（最新博客在前），解决 feed 正序 bug
        Set<ZSetOperations.TypedTuple<String>> scores = stringRedisTemplate.opsForZSet()
                .reverseRangeByScoreWithScores(key, 0, max, offset, pageSize);
        log.info("Feed ZSet 查询结果:{}", scores);
        // ZSet 为空（首次访问或历史关注未回填），触发懒回填
        if (scores == null || scores.isEmpty()) {
            log.info("Feed ZSet 为空，触发懒回填，userId={}", userId);
            generateFeedForUser(userId);
            // 重试一次
            scores = stringRedisTemplate.opsForZSet()
                    .reverseRangeByScoreWithScores(key, 0, max, offset, pageSize);
            if (scores == null || scores.isEmpty()) {
                return Result.ok();
            }
        }
        long min = Long.MAX_VALUE;
        List<Long> ids = new ArrayList<>(scores.size());
        int os = 1;
        for (ZSetOperations.TypedTuple<String> score : scores) {
            ids.add(Long.valueOf(Objects.requireNonNull(score.getValue())));
            long time = Objects.requireNonNull(score.getScore()).longValue();
            // 统计这批结果中的最小时间戳（即最后一条博客的时间），以及该时间戳的重复个数
            if (time == min) {
                os++;
            } else if (time < min) {
                min = time;
                os = 1;
            }
        }
        String idStr = StringUtil.join(ids, ",");
        List<Blog> blogList = blogMapper.selectList(new LambdaQueryWrapper<Blog>()
                .in(Blog::getId, ids)
                .ne(Blog::getImages, "")
                .last("order by field(id," + idStr + ")"));
        log.info("已查询到博客");
        for (Blog blog : blogList) {
            setUserToBlog(blog);
            isLiked(blog);
        }
        result.setOffset(os);
        result.setMinTime(min);
        result.setList(blogList);
        log.info("即将返回页面对象{}", result);
        return Result.ok(result);
    }

    /**
     * 懒回填：把当前用户关注的所有账号的历史博客写入 feed ZSet
     * <p>
     * 在 feed 首次查询且 ZSet 为空时触发，解决历史关注无数据的问题。
     * 每个被关注者最多回填最近 20 篇已发布的博客。
     */
    private void generateFeedForUser(Long userId) {
        List<Follow> follows = followMapper.selectList(
                new LambdaQueryWrapper<Follow>().eq(Follow::getUserId, userId));
        if (follows == null || follows.isEmpty()) {
            log.info("懒回填：用户 {} 没有关注任何人", userId);
            return;
        }
        String feedKey = RedisConstants.FEED_KEY + userId;
        int totalPushed = 0;
        for (Follow follow : follows) {
            List<Blog> blogs = blogMapper.selectList(new LambdaQueryWrapper<Blog>()
                    .eq(Blog::getUserId, follow.getFollowUserId())
                    .ne(Blog::getImages, "")
                    .orderByDesc(Blog::getCreateTime)
                    .last("LIMIT 20"));
            for (Blog blog : blogs) {
                double score = (double) blog.getCreateTime()
                        .toInstant(ZoneOffset.UTC).toEpochMilli();
                stringRedisTemplate.opsForZSet().add(
                        feedKey, String.valueOf(blog.getId()), score);
            }
            totalPushed += blogs.size();
        }
        stringRedisTemplate.expire(feedKey, RedisConstants.FOLLOWS_TTL, TimeUnit.SECONDS);
        log.info("懒回填完成：用户 {} 共回填 {} 篇博客到 feed", userId, totalPushed);
    }

    /** 填充作者昵称/头像（nickName、icon 已迁移到 tb_user_info） */
    private void setUserToBlog(Blog blog) {
        Long userId = blog.getUserId();
        UserInfo userInfo = userInfoService.getById(userId);
        blog.setName(userInfo != null ? userInfo.getNickName() : "");
        blog.setIcon(userInfo != null ? userInfo.getIcon() : "");
    }

    /** 填充当前用户是否已点赞 */
    private void isLiked(Blog blog) {
        UserDTO userDTO = UserHolder.getUserDTO();
        if (userDTO == null) {
            return;
        }
        Long userId = userDTO.getId();
        String userKey = RedisConstants.USER_LIKED_KEY + userId;
        Boolean isMember = stringRedisTemplate.opsForSet().isMember(userKey, String.valueOf(blog.getId()));
        blog.setIsLike(Boolean.TRUE.equals(isMember));
    }
}
