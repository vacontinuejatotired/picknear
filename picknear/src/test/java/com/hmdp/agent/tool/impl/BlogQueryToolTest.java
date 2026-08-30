package com.hmdp.agent.tool.impl;

import com.baomidou.mybatisplus.extension.conditions.query.QueryChainWrapper;
import com.hmdp.dto.Result;
import com.hmdp.content.blog.BlogQueryService;
import com.hmdp.content.entity.Blog;
import com.hmdp.content.entity.BlogComments;
import com.hmdp.content.service.IBlogCommentsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * BlogQueryTool — 博客查询工具测试。
 */
@ExtendWith(MockitoExtension.class)
class BlogQueryToolTest {

    @Mock private BlogQueryService blogQueryService;
    @Mock private IBlogCommentsService blogCommentsService;

    private BlogQueryTool tool;

    @BeforeEach
    void setUp() {
        tool = new BlogQueryTool();
        ReflectionTestUtils.setField(tool, "blogQueryService", blogQueryService);
        ReflectionTestUtils.setField(tool, "blogCommentsService", blogCommentsService);
    }

    @Test
    void queryBlogById_should_return_detail_with_author() {
        Blog b = new Blog().setId(7L).setTitle("探店").setContent("a".repeat(100)).setLiked(5).setComments(2)
                .setName("作者甲");
        when(blogQueryService.queryById(7L)).thenReturn(Result.ok(b));

        BlogQueryTool.BlogBrief result = tool.queryBlogById(7L);

        assertThat(result).as("应返回博客详情").isNotNull();
        assertThat(result.title()).isEqualTo("探店");
        assertThat(result.authorName()).as("应带作者昵称").isEqualTo("作者甲");
        assertThat(result.content()).as("长内容应截断").hasSizeLessThanOrEqualTo(83);
    }

    @Test
    void queryBlogById_should_return_null_when_not_found() {
        when(blogQueryService.queryById(7L)).thenReturn(Result.fail("博客不存在"));

        BlogQueryTool.BlogBrief result = tool.queryBlogById(7L);

        assertThat(result).as("查不到应返回 null").isNull();
    }

    @Test
    void queryBlogComments_should_return_ordered_comments() {
        when(blogCommentsService.listByBlogIdOrderByCreateTime(7L))
                .thenReturn(List.of(new BlogComments().setId(1L).setUserId(2L)
                        .setContent("不错的笔记").setParentId(0L)));

        List<BlogQueryTool.BlogCommentBrief> result = tool.queryBlogComments(7L);

        assertThat(result).as("应返回评论列表").hasSize(1);
        assertThat(result.get(0).content()).isEqualTo("不错的笔记");
        assertThat(result.get(0).parentId()).isEqualTo(0L);
    }

    @Test
    void queryUserBlogs_should_return_limited_blogs() {
        Blog blog = new Blog().setId(1L).setTitle("用户博客").setContent("内容").setLiked(2).setName("作者乙");
        when(blogQueryService.queryByUserId(3L, 1)).thenReturn(Result.ok(List.of(blog)));

        List<BlogQueryTool.BlogBrief> result = tool.queryUserBlogs(3L);

        assertThat(result).as("应返回用户博客").hasSize(1);
        assertThat(result.get(0).title()).isEqualTo("用户博客");
    }
}
