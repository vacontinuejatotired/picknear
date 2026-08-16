package com.hmdp.content.blog;

import cn.hutool.json.JSONUtil;
import com.hmdp.common.cache.CacheManager;
import com.hmdp.content.dto.BlogFormDTO;
import com.hmdp.content.entity.Blog;
import com.hmdp.content.feed.FeedPushService;
import com.hmdp.content.mapper.BlogMapper;
import com.hmdp.dto.Result;
import com.hmdp.utils.UserHolder;
import com.hmdp.utils.redis.RedisConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import jakarta.annotation.Resource;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 博客发布服务 — 发布/更新图片（content 域收敛，P3-S4）
 * <p>
 * 职责（自 BlogServiceImpl 迁出）：
 * <ul>
 *   <li>saveBlog：创建草稿（初始无图）+ 写缓存，不推 Feed（等图片上传）；请求体为 {@link BlogFormDTO}（H-1 修复）</li>
 *   <li>updateBlogImages：作者校验 + 事务内更新图片；Feed 推送移出事务（afterCommit，H-5 修复）</li>
 * </ul>
 * 缓存写入经 {@link CacheManager}（随机 TTL 防雪崩，P4 博客缓存收敛）。
 * </p>
 */
@Slf4j
@Component
public class BlogPublishService {

    @Resource
    private BlogMapper blogMapper;
    @Resource
    private CacheManager cacheManager;
    @Resource
    private FeedPushService feedPushService;

    /** 发布博客（创建草稿，图片为空，暂不推 Feed） */
    public Result saveBlog(BlogFormDTO dto) {
        Blog blog = new Blog();
        blog.setUserId(UserHolder.getUserId());
        blog.setTitle(dto.getTitle());
        blog.setContent(dto.getContent());
        blog.setShopId(dto.getShopId());
        blog.setImages("");          // 初始无图片，创建草稿
        int inserted = blogMapper.insert(blog);
        if (inserted <= 0) {
            return Result.fail("新增笔记失败");
        }
        // 写入 Redis 缓存（随机 TTL 防雪崩）
        cacheManager.setWithJitter(RedisConstants.CACHE_BLOG_KEY + blog.getId(), blog,
                RedisConstants.CACHE_BLOG_TTL, TimeUnit.MINUTES);
        // 注意：此时不推送 Feed，等图片上传完成后 updateBlogImages 再推送
        return Result.ok(blog.getId());
    }

    /** 更新博客图片列表（作者校验；首次设置图片时推 Feed 给粉丝） */
    @Transactional(rollbackFor = Exception.class)
    public Result updateBlogImages(Long id, List<String> images) {
        // 1. 校验博客存在
        Blog blog = blogMapper.selectById(id);
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
        int updated = blogMapper.updateById(blog);
        if (updated <= 0) {
            return Result.fail("更新失败");
        }
        // 4. 更新 Redis 缓存（随机 TTL 防雪崩）
        cacheManager.setWithJitter(RedisConstants.CACHE_BLOG_KEY + id, blog,
                RedisConstants.CACHE_BLOG_TTL, TimeUnit.MINUTES);
        log.info("博客缓存已更新, blogId={}", id);
        // 5. 推 Feed 给粉丝 — 移出事务（H-5：不在事务内发外部副作用），
        //    事务提交后执行；无事务环境直接推送
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    feedPushService.pushToFans(userId, id);
                }
            });
        } else {
            feedPushService.pushToFans(userId, id);
        }
        log.info("博客图片更新成功, blogId={}, images={}", id, imagesStr);
        return Result.ok();
    }
}
