package com.hmdp.agent.tool.impl;

import com.hmdp.content.blog.BlogPublishService;
import com.hmdp.content.blog.BlogQueryService;
import com.hmdp.content.dto.BlogFormDTO;
import com.hmdp.content.entity.Blog;
import com.hmdp.dto.Result;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * BlogTool — 博客工具测试。
 * <p>
 * 覆盖 queryPublishedBlogs、publishTestBlog、queryBlogsByTitle。
 * P3-S4 后改 mock BlogQueryService / BlogPublishService。
 */
@ExtendWith(MockitoExtension.class)
class BlogToolTest {

    @Mock
    private BlogQueryService blogQueryService;

    @Mock
    private BlogPublishService blogPublishService;

    @Mock
    private ToolContext toolContext;

    private BlogTool blogTool;

    @BeforeEach
    void setUp() {
        blogTool = new BlogTool();
        ReflectionTestUtils.setField(blogTool, "blogQueryService", blogQueryService);
        ReflectionTestUtils.setField(blogTool, "blogPublishService", blogPublishService);
    }

    @Test
    void queryPublishedBlogs_should_return_user_blogs() {
        when(toolContext.getContext()).thenReturn(Map.of("userId", 1L));
        when(blogQueryService.countByUserId(1L)).thenReturn(7L);
        when(blogQueryService.queryPublishedByUserId(1L, 5))
                .thenReturn(List.of(new Blog().setTitle("标题").setContent("a".repeat(100)).setLiked(3)));

        List<BlogTool.BlogBrief> result = blogTool.queryPublishedBlogs(toolContext);

        assertThat(result).as("应返回用户博客紧凑投影").hasSize(1);
        BlogTool.BlogBrief brief = result.get(0);
        assertThat(brief.title()).as("投影标题").isEqualTo("标题");
        assertThat(brief.liked()).as("投影点赞数").isEqualTo(3);
        assertThat(brief.total()).as("投影总数").isEqualTo(7L);
        assertThat(brief.content()).as("超长内容应截断（80字+省略号）").hasSizeLessThanOrEqualTo(83);
    }

    @Test
    void queryPublishedBlogs_should_limit_to_5() {
        when(toolContext.getContext()).thenReturn(Map.of("userId", 1L));
        when(blogQueryService.countByUserId(1L)).thenReturn(0L);
        when(blogQueryService.queryPublishedByUserId(1L, 5)).thenReturn(List.of());

        blogTool.queryPublishedBlogs(toolContext);

        verify(blogQueryService).queryPublishedByUserId(1L, 5);
    }

    @Test
    void publishTestBlog_should_save_and_return() {
        when(toolContext.getContext()).thenReturn(Map.of("userId", 1L));
        when(blogPublishService.saveBlog(any(BlogFormDTO.class))).thenReturn(Result.ok(1L));

        Blog result = blogTool.publishTestBlog(toolContext);

        assertThat(result).as("save 成功应返回 Blog 对象").isNotNull();
        assertThat(result.getTitle()).as("标题应为 '测试博客'").isEqualTo("测试博客");
    }

    @Test
    void publishTestBlog_should_return_null_when_save_fails() {
        when(toolContext.getContext()).thenReturn(Map.of("userId", 1L));
        when(blogPublishService.saveBlog(any(BlogFormDTO.class))).thenReturn(Result.fail("新增笔记失败"));

        Blog result = blogTool.publishTestBlog(toolContext);

        assertThat(result).as("save 失败应返回 null").isNull();
    }

    @Test
    void queryBlogsByTitle_should_return_matches() {
        when(blogQueryService.countByTitle("旅游")).thenReturn(1L);
        when(blogQueryService.queryByTitle("旅游", 10))
                .thenReturn(List.of(new Blog().setTitle("旅游攻略").setContent("内容").setLiked(2)));

        List<BlogTool.BlogBrief> result = blogTool.queryBlogsByTitle("旅游");

        assertThat(result).as("应返回匹配博客的紧凑投影").hasSize(1);
        assertThat(result.get(0).title()).isEqualTo("旅游攻略");
        assertThat(result.get(0).total()).isEqualTo(1L);
    }
}
