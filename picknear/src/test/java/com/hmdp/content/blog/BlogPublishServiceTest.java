package com.hmdp.content.blog;

import com.hmdp.common.cache.CacheManager;
import com.hmdp.content.dto.BlogFormDTO;
import com.hmdp.content.entity.Blog;
import com.hmdp.content.feed.FeedPushService;
import com.hmdp.content.mapper.BlogMapper;
import com.hmdp.dto.Result;
import com.hmdp.utils.UserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * BlogPublishService — 博客发布服务测试（P3-S4 + B1/B2）。
 * 覆盖：DTO 字段映射、作者校验拒绝、afterCommit 推 Feed（事务外）。
 */
@ExtendWith(MockitoExtension.class)
class BlogPublishServiceTest {

    @Mock
    private BlogMapper blogMapper;

    @Mock
    private CacheManager cacheManager;

    @Mock
    private FeedPushService feedPushService;

    private BlogPublishService service;

    @BeforeEach
    void setUp() {
        service = new BlogPublishService();
        ReflectionTestUtils.setField(service, "blogMapper", blogMapper);
        ReflectionTestUtils.setField(service, "cacheManager", cacheManager);
        ReflectionTestUtils.setField(service, "feedPushService", feedPushService);
        UserHolder.saveUserId(7L);
    }

    @AfterEach
    void tearDown() {
        UserHolder.remove();
    }

    @Test
    void saveBlog_should_map_dto_and_force_server_fields() {
        BlogFormDTO dto = new BlogFormDTO();
        dto.setTitle("探店");
        dto.setContent("内容");
        dto.setShopId(3L);
        when(blogMapper.insert(any(Blog.class))).thenAnswer(inv -> {
            Blog b = inv.getArgument(0);
            b.setId(100L);
            return 1;
        });

        Result result = service.saveBlog(dto);

        assertThat(result.getSuccess()).as("发布应成功").isTrue();
        assertThat(result.getData()).isEqualTo(100L);
        ArgumentCaptor<Blog> captor = ArgumentCaptor.forClass(Blog.class);
        verify(blogMapper).insert(captor.capture());
        Blog saved = captor.getValue();
        assertThat(saved.getUserId()).as("userId 服务端强制").isEqualTo(7L);
        assertThat(saved.getImages()).as("images 服务端强制为空草稿").isEmpty();
        assertThat(saved.getTitle()).isEqualTo("探店");
        // 草稿阶段不推 Feed（等图片上传）
        verify(feedPushService, never()).pushToFans(anyLong(), anyLong());
    }

    @Test
    void updateBlogImages_should_reject_non_owner() {
        Blog blog = new Blog().setId(100L).setUserId(99L);
        when(blogMapper.selectById(100L)).thenReturn(blog);

        Result result = service.updateBlogImages(100L, List.of("url1"));

        assertThat(result.getSuccess()).as("非作者应拒绝").isFalse();
        verify(blogMapper, never()).updateById(any(Blog.class));
    }

    @Test
    void updateBlogImages_should_push_feed_after_commit() {
        Blog blog = new Blog().setId(100L).setUserId(7L);
        when(blogMapper.selectById(100L)).thenReturn(blog);
        when(blogMapper.updateById(any(Blog.class))).thenReturn(1);

        // 无事务环境：TransactionSynchronizationManager 未激活 → 直接推送（等价语义分支）
        Result result = service.updateBlogImages(100L, List.of("url1"));

        assertThat(result.getSuccess()).as("作者本人更新应成功").isTrue();
        verify(feedPushService).pushToFans(7L, 100L);
    }
}
