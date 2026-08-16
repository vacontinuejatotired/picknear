package com.hmdp.content.blog;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.content.entity.Blog;
import com.hmdp.content.mapper.BlogMapper;
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
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 博客查询服务 — 查询 + 动态装配（content 域收敛，P3-S3）
 * <p>
 * 自 BlogServiceImpl 查询方法迁出（行为等价）：
 * <ul>
 *   <li>queryById：缓存穿透/雪崩防护（空值缓存 + 随机 TTL）</li>
 *   <li>queryHotById / queryByUserId / queryMyBlog：分页查询</li>
 *   <li>queryUserList：点赞 TopN 用户列表</li>
 *   <li>setUserToBlog / isLiked：作者信息与点赞状态装配（FeedQueryService 复用，单一来源）</li>
 * </ul>
 * </p>
 */
@Slf4j
@Component
public class BlogQueryService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private BlogMapper blogMapper;
    @Resource
    private IUserInfoService userInfoService;

    /** 博客详情（缓存穿透/雪崩防护） */
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
        Blog blog = blogMapper.selectById(id);
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

    /** 热门博客分页（有图、liked 降序） */
    public Result queryHotById(Integer current) {
        Page<Blog> page = blogMapper.selectPage(new Page<>(current, SystemConstants.MAX_PAGE_SIZE),
                new LambdaQueryWrapper<Blog>()
                        .orderByDesc(Blog::getLiked, Blog::getId)
                        .ne(Blog::getImages, ""));
        List<Blog> records = page.getRecords();
        records.forEach(blog -> {
            this.setUserToBlog(blog);
            this.isLiked(blog);
        });
        return Result.ok(records);
    }

    /** 某用户的所有笔记（分页） */
    public Result queryByUserId(Long id, Integer current) {
        Page<Blog> page = blogMapper.selectPage(new Page<>(current, SystemConstants.MAX_PAGE_SIZE),
                new LambdaQueryWrapper<Blog>()
                        .eq(Blog::getUserId, id)
                        .ne(Blog::getImages, ""));
        List<Blog> records = page.getRecords();
        records.forEach(blog -> {
            setUserToBlog(blog);
            isLiked(blog);
        });
        return Result.ok(records);
    }

    /** 当前登录用户的笔记（分页） */
    public Result queryMyBlog(Integer current) {
        Long userId = UserHolder.getUserId();
        Page<Blog> page = blogMapper.selectPage(new Page<>(current, SystemConstants.MAX_PAGE_SIZE),
                new LambdaQueryWrapper<Blog>().eq(Blog::getUserId, userId));
        List<Blog> records = page.getRecords();
        records.forEach(blog -> {
            setUserToBlog(blog);
            isLiked(blog);
        });
        return Result.ok(records);
    }

    /** 点赞 TopN 用户列表（ZSet 前 5） */
    public Result queryUserList(Long id) {
        Blog blog = blogMapper.selectById(id);
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

    /** 填充作者昵称/头像（nickName、icon 已迁移到 tb_user_info） */
    public void setUserToBlog(Blog blog) {
        Long userId = blog.getUserId();
        UserInfo userInfo = userInfoService.getById(userId);
        blog.setName(userInfo != null ? userInfo.getNickName() : "");
        blog.setIcon(userInfo != null ? userInfo.getIcon() : "");
    }

    /** 填充当前用户是否已点赞 */
    public void isLiked(Blog blog) {
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
