package com.hmdp.content.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.content.entity.Blog;
import com.hmdp.content.feed.FeedPushService;
import com.hmdp.content.mapper.BlogMapper;
import com.hmdp.content.service.IBlogService;
import com.hmdp.dto.Result;
import com.hmdp.user.dto.UserDTO;
import com.hmdp.user.entity.UserInfo;
import com.hmdp.user.service.IUserInfoService;
import com.hmdp.utils.UserHolder;
import com.hmdp.utils.constants.SystemConstants;
import com.hmdp.utils.redis.RedisConstants;
import jodd.util.StringUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 探店笔记服务实现 — 查询/点赞/发布（P3-S1 拆分后）
 * <p>
 * 职责边界（2026-08 P3 拆分）：
 * <ul>
 *   <li>查询/点赞/发布保留；Feed 推/读已迁 {@link FeedPushService} / {@code FeedQueryService}</li>
 *   <li>不再依赖 IFollowService（粉丝查询下沉 FeedPushService，循环依赖解除）</li>
 * </ul>
 * </p>
 */
@Service
@Slf4j
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {
    @Resource
    private IUserInfoService userInfoService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private FeedPushService feedPushService;

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
        Blog blog = getById(id);
        String key = RedisConstants.BLOG_LIKED_KEY + blog.getId();
        Set<String> userDTOList = stringRedisTemplate.opsForZSet().range(key, 0, 4);
        if (userDTOList == null || userDTOList.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }
        List<Long> userIds = userDTOList.stream().map(Long::valueOf).toList();
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
        // 5. 首次设置图片时推送 Feed 给粉丝（粉丝查询下沉 FeedPushService）
        feedPushService.pushToFans(userId, id);
        log.info("博客图片更新成功, blogId={}, images={}", id, imagesStr);
        return Result.ok();
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
}
