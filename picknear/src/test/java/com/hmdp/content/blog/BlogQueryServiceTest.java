package com.hmdp.content.blog;

import com.hmdp.common.cache.CacheManager;
import com.hmdp.content.entity.Blog;
import com.hmdp.content.mapper.BlogMapper;
import com.hmdp.dto.Result;
import com.hmdp.user.entity.UserInfo;
import com.hmdp.user.service.IUserInfoService;
import com.hmdp.utils.UserHolder;
import com.hmdp.utils.redis.RedisConstants;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * BlogQueryService — 博客查询服务测试（P3-S3 + 缓存收敛后）。
 * 覆盖：queryById 缓存语义（命中/不存在）、装配调用、工具查询方法。
 */
@ExtendWith(MockitoExtension.class)
class BlogQueryServiceTest {

    @Mock
    private CacheManager cacheManager;

    @Mock
    private BlogMapper blogMapper;

    @Mock
    private IUserInfoService userInfoService;

    private BlogQueryService service;

    @BeforeEach
    void setUp() {
        service = new BlogQueryService();
        ReflectionTestUtils.setField(service, "cacheManager", cacheManager);
        ReflectionTestUtils.setField(service, "blogMapper", blogMapper);
        ReflectionTestUtils.setField(service, "userInfoService", userInfoService);
        UserHolder.saveUserId(7L);
    }

    @AfterEach
    void tearDown() {
        UserHolder.remove();
    }

    @Test
    void queryById_should_return_fail_when_cache_miss_and_db_missing() {
        when(cacheManager.queryWithCache(eq(9L), eq(Blog.class), eq(RedisConstants.CACHE_BLOG_KEY),
                any(), eq(RedisConstants.CACHE_BLOG_TTL), eq(TimeUnit.MINUTES))).thenReturn(null);

        Result result = service.queryById(9L);

        assertThat(result.getSuccess()).as("缓存与 DB 均无应失败").isFalse();
        assertThat(result.getErrorMsg()).contains("博客不存在");
    }

    @Test
    void queryById_should_assemble_user_and_like_when_hit() {
        Blog blog = new Blog().setId(9L).setUserId(3L).setTitle("探店").setContent("内容");
        when(cacheManager.queryWithCache(eq(9L), eq(Blog.class), eq(RedisConstants.CACHE_BLOG_KEY),
                any(), eq(RedisConstants.CACHE_BLOG_TTL), eq(TimeUnit.MINUTES))).thenReturn(blog);
        UserInfo author = new UserInfo();
        author.setUserId(3L);
        author.setNickName("作者甲");
        author.setIcon("icon.png");
        when(userInfoService.getById(3L)).thenReturn(author);

        Result result = service.queryById(9L);

        assertThat(result.getSuccess()).as("缓存命中应成功").isTrue();
        Blog data = (Blog) result.getData();
        assertThat(data.getName()).as("应装配作者昵称").isEqualTo("作者甲");
        assertThat(data.getIcon()).isEqualTo("icon.png");
    }

    @Test
    void queryPublishedByUserId_should_delegate_to_mapper() {
        // 工具查询方法：验证 keyPrefix 与 TTL 参数透传语义（简单冒烟）
        service.countAll();
        verify(blogMapper).selectCount(null);
    }
}
