package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.hmdp.service.IFollowService;
import com.hmdp.user.dto.UserDTO;
import com.hmdp.user.entity.UserInfo;
import com.hmdp.user.service.IUserInfoService;
import com.hmdp.utils.redis.RedisConstants;
import com.hmdp.utils.constants.SystemConstants;
import com.hmdp.utils.UserHolder;
import jodd.util.StringUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
/**
 * 探店笔记服务实现 — 笔记CRUD、点赞（Redis ZSet）、关注者Feed流（推模式）
 */
@Service
@Slf4j
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {
    @Resource
    private IFollowService followService;
    @Resource
    private IUserInfoService userInfoService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryById(Long id) {
        String key = RedisConstants.CACHE_BLOG_KEY + id;
        // 1. 优先查 Redis
        String json = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isNotBlank(json)) {
            Blog blog = JSONUtil.toBean(json, Blog.class);
            setUserToBlog(blog);
            isLiked(blog);
            return Result.ok(blog);
        }
        // 2. 空值缓存命中（缓存穿透防护）
        if (json != null) {
            return Result.fail("博客不存在");
        }
        // 3. 查 MySQL
        Blog blog = getById(id);
        if (blog == null) {
            stringRedisTemplate.opsForValue().set(key, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
            return Result.fail("博客不存在");
        }
        // 4. 写入 Redis（过期时间加随机偏移，防缓存雪崩）
        long ttl = RedisConstants.CACHE_BLOG_TTL + (long) (Math.random() * RedisConstants.CACHE_BLOG_TTL);
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(blog), ttl, TimeUnit.MINUTES);
        // 5. 填充动态字段后返回
        setUserToBlog(blog);
        isLiked(blog);
        return Result.ok(blog);
    }

    @Override
    public Result queryHotById(Integer current) {
        Page<Blog> page = query()
                .orderByDesc("liked", "id")
                .ne("images", "")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(blog -> {
            this.setUserToBlog(blog);
            this.isLiked(blog);
        });
        return Result.ok(records);
    }

    private void setUserToBlog(Blog blog) {
        Long userId = blog.getUserId();
        // nickName、icon 已从 tb_user 迁移到 tb_user_info
        UserInfo userInfo = userInfoService.getById(userId);
        blog.setName(userInfo != null ? userInfo.getNickName() : "");
        blog.setIcon(userInfo != null ? userInfo.getIcon() : "");
    }

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

    //TODO 点赞有bug  ，一人一赞没实现，还有取消点赞再点还是取消
    @Override
    public Result likeBlog(Long id) {
        Long userId = UserHolder.getUserId();
        String zsetKey = RedisConstants.BLOG_LIKED_KEY + id;
        String userKey = RedisConstants.USER_LIKED_KEY + userId;
        String lockKey = "lock:like:" + id + ":" + userId;

        // 分布式锁，防并发重复点赞/取消
        Boolean locked = stringRedisTemplate.opsForValue().setIfAbsent(lockKey, "1", 3, TimeUnit.SECONDS);
        if (Boolean.FALSE.equals(locked)) {
            return Result.fail("操作太频繁，请稍后再试");
        }
        try {
            // 优先查 Set（用户维度），ZSet 只用于 TopN 查询
            Boolean isLiked = stringRedisTemplate.opsForSet().isMember(userKey, String.valueOf(id));
            if (Boolean.FALSE.equals(isLiked)) {
                boolean update = update().setSql("liked = liked + 1").eq("id", id).update();
                if (update) {
                    stringRedisTemplate.opsForSet().add(userKey, String.valueOf(id));
                    stringRedisTemplate.opsForZSet().add(zsetKey, userId.toString(), System.currentTimeMillis());
                }
            } else {
                boolean update = update().setSql("liked = liked - 1").eq("id", id).update();
                if (update) {
                    stringRedisTemplate.opsForSet().remove(userKey, String.valueOf(id));
                    stringRedisTemplate.opsForZSet().remove(zsetKey, userId.toString());
                }
            }
            // 同步刷新博客缓存中的 liked 数
            Blog blog = getById(id);
            if (blog != null) {
                String cacheKey = RedisConstants.CACHE_BLOG_KEY + id;
                long ttl = RedisConstants.CACHE_BLOG_TTL + (long) (Math.random() * RedisConstants.CACHE_BLOG_TTL);
                stringRedisTemplate.opsForValue().set(cacheKey, JSONUtil.toJsonStr(blog), ttl, TimeUnit.MINUTES);
            }
        } finally {
            stringRedisTemplate.delete(lockKey);
        }
        return Result.ok();
    }

    @Override
    public Result queryUserList(Long id) {
        Set<String> userDTOList = new HashSet<>();
        Blog blog = getById(id);
        String key = RedisConstants.BLOG_LIKED_KEY + blog.getId();
        userDTOList = stringRedisTemplate.opsForZSet().range(key, 0, 4);
        if (userDTOList == null || userDTOList.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }
        List<Long> userIds = new ArrayList<>();
        userIds = userDTOList.stream().map(Long::valueOf).toList();
        String idStr = StringUtil.join(userIds, ",");

        List<UserDTO> userDTOS = userInfoService.query()
                .in("user_id", userDTOList)
                .last("order by field (user_id," + idStr + ")")
                .list()
                .stream()
                .map(info -> {
                    UserDTO dto = BeanUtil.copyProperties(info, UserDTO.class);
                    dto.setId(info.getUserId());
                    return dto;
                })
                .toList();
        return Result.ok(userDTOS);
    }

    @Override
    public Result saveBlog(Blog blog) {
        Long user = UserHolder.getUserId();
        blog.setUserId(user);
        blog.setImages("");          // 初始无图片，创建草稿
        boolean isSuccess = save(blog);
        if (!isSuccess) {
            return Result.fail("新增笔记失败");
        }
        // 写入 Redis 缓存
        String key = RedisConstants.CACHE_BLOG_KEY + blog.getId();
        long ttl = RedisConstants.CACHE_BLOG_TTL + (long) (Math.random() * RedisConstants.CACHE_BLOG_TTL);
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(blog), ttl, TimeUnit.MINUTES);
        // 注意：此时不推送 Feed，等图片上传完成后 updateBlogImages 再推送
        return Result.ok(blog.getId());
    }

    @Override
    @Transactional
    public Result updateBlogImages(Long id, List<String> images) {
        // 1. 校验博客存在
        Blog blog = getById(id);
        if (blog == null) {
            return Result.fail("博客不存在");
        }
        // 2. 校验作者身份 (S3)
        Long userId = UserHolder.getUserId();
        if (!userId.equals(blog.getUserId())) {
            return Result.fail("无权修改他人博客");
        }
        // 3. List<String> → 逗号分隔字符串（API 用 JSON 数组，DB 兼容存量数据）
        String imagesStr = (images == null || images.isEmpty()) ? "" : String.join(",", images);
        blog.setImages(imagesStr);
        boolean ok = updateById(blog);
        if (!ok) {
            return Result.fail("更新失败");
        }
        // 4. 更新 Redis 缓存
        String key = RedisConstants.CACHE_BLOG_KEY + id;
        long ttl = RedisConstants.CACHE_BLOG_TTL + (long) (Math.random() * RedisConstants.CACHE_BLOG_TTL);
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(blog), ttl, TimeUnit.MINUTES);
        log.info("博客缓存已更新, blogId={}", id);
        // 5. 首次设置图片时推送 Feed 给粉丝
        List<Follow> follows = followService.query().eq("follow_user_id", userId).list();
        pushBloToFansBatch(follows, id);
        log.info("博客图片更新成功, blogId={}, images={}", id, imagesStr);
        return Result.ok();
    }
    //抽取方法，实现批量插入，防止N次连接
    private void  pushBloToFansBatch(List<Follow> follows,Long blogId) {
        if (follows == null || follows.isEmpty()) {
            log.info("Empty follows");
            return;
        }
        log.info("开始批量插入");
        stringRedisTemplate.executePipelined((RedisCallback<Object>) connect ->{
            Long followUserId ;
            double now = System.currentTimeMillis();
            String key;
            byte []keyByte;
            byte []valueByte= blogId.toString().getBytes();
        for (Follow follow : follows) {
            followUserId = follow.getUserId();
            key = "feed:"+followUserId;
            keyByte = key.getBytes(StandardCharsets.UTF_8);
            connect.zAdd(keyByte,now,valueByte);
        }
      return null;
        } );
        return ;
    }

    @Override
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
        List<Blog> blogList = query().in("id", ids)
                .ne("images", "")
                .last("order by field(id," + idStr + ")")
                .list();
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

    @Override
    public Result queryByUserId(Long id, Integer current) {
        Page<Blog> page = query()
                .eq("user_id", id)
                .ne("images", "")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        List<Blog> records = page.getRecords();
        records.forEach(blog -> {
            setUserToBlog(blog);
            isLiked(blog);
        });
        return Result.ok(records);
    }

    @Override
    public Result queryMyBlog(Integer current) {
        Long userId = UserHolder.getUserId();
        Page<Blog> page = query()
                .eq("user_id", userId)
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        List<Blog> records = page.getRecords();
        records.forEach(blog -> {
            setUserToBlog(blog);
            isLiked(blog);
        });
        return Result.ok(records);
    }

    /**
     * 懒回填：把当前用户关注的所有账号的历史博客写入 feed ZSet
     * <p>
     * 在 feed 首次查询且 ZSet 为空时触发，解决历史关注无数据的问题。
     * 每个被关注者最多回填最近 20 篇已发布的博客。
     */
    private void generateFeedForUser(Long userId) {
        List<Follow> follows = followService.query().eq("user_id", userId).list();
        if (follows == null || follows.isEmpty()) {
            log.info("懒回填：用户 {} 没有关注任何人", userId);
            return;
        }
        String feedKey = RedisConstants.FEED_KEY + userId;
        int totalPushed = 0;
        for (Follow follow : follows) {
            List<Blog> blogs = query()
                    .eq("user_id", follow.getFollowUserId())
                    .ne("images", "")
                    .orderByDesc("create_time")
                    .last("LIMIT 20")
                    .list();
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
}
