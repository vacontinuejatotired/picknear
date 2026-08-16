package com.hmdp.content.service.impl;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.content.entity.Blog;
import com.hmdp.content.feed.FeedPushService;
import com.hmdp.content.mapper.BlogMapper;
import com.hmdp.content.service.IBlogService;
import com.hmdp.dto.Result;
import com.hmdp.utils.UserHolder;
import com.hmdp.utils.redis.RedisConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.Resource;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 探店笔记服务实现 — 发布域（P3-S3 拆分后）
 * <p>
 * 查询已迁 {@code BlogQueryService}、点赞迁 {@code BlogLikeService}、Feed 迁 feed 域；
 * 本类仅剩 saveBlog/updateBlogImages，P3-S5 迁 {@code BlogPublishService} 后删除。
 * </p>
 */
@Service
@Slf4j
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private FeedPushService feedPushService;

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
}
