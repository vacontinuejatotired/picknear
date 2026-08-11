package com.hmdp.agent.tool.impl;

import java.util.List;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.agent.annotation.TargetTool;
import com.hmdp.agent.util.TextUtils;
import com.hmdp.dto.Result;
import com.hmdp.entity.Blog;
import com.hmdp.entity.BlogComments;
import com.hmdp.service.IBlogCommentsService;
import com.hmdp.service.IBlogService;
import com.hmdp.utils.constants.SystemConstants;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * 博客查询工具 — 博客详情 / 博客评论 / 某用户博客。
 * 供长任务串链：用户 → 博客 → 详情/评论。
 */
@TargetTool(active = true)
@Slf4j
public class BlogQueryTool {

    @Resource
    private IBlogService blogService;

    @Resource
    private IBlogCommentsService blogCommentsService;

    public record BlogBrief(Long id, String title, String content, Integer liked, Integer comments,
                            String authorName) {}

    public record BlogCommentBrief(Long id, Long userId, String content, Long parentId, String createTime) {}

    /**
     * 查询单篇博客详情（queryById 已填充作者昵称）。
     */
    @Tool(description = """
            查询单篇博客的详细信息（标题/内容摘要/点赞数/评论数/作者昵称），和「这篇博客」「博客详情」「看看这篇」一起使用。
            博客ID来自 queryUserBlogs 或 queryBlogsByTitle。
            """)
    public BlogBrief queryBlogById(
            @ToolParam(description = "博客ID") Long blogId) {
        Result result = blogService.queryById(blogId);
        if (result == null || !Boolean.TRUE.equals(result.getSuccess())
                || !(result.getData() instanceof Blog b)) {
            return null;
        }
        return new BlogBrief(b.getId(), b.getTitle(), TextUtils.truncate(b.getContent(), 80),
                b.getLiked(), b.getComments(), b.getName());
    }

    /**
     * 查询某篇博客的评论列表（按时间升序）。
     */
    @Tool(description = """
            查询某篇博客的评论列表，和「看看评论」「这条博客的评论」一起使用。
            返回评论ID/评论用户/内容/是否一级评论/时间。博客ID来自 queryBlogById。
            """)
    public List<BlogCommentBrief> queryBlogComments(
            @ToolParam(description = "博客ID") Long blogId) {
        List<BlogComments> list = blogCommentsService.query().eq("blog_id", blogId)
                .orderByAsc("create_time").list();
        return list.stream()
                .map(c -> new BlogCommentBrief(c.getId(), c.getUserId(),
                        TextUtils.truncate(c.getContent(), 120),
                        c.getParentId(), String.valueOf(c.getCreateTime())))
                .toList();
    }

    /**
     * 查询某个用户发布的博客列表（不限当前登录用户，前10条）。
     */
    @Tool(description = """
            查询某个用户发布的博客列表（可查别人，不限于自己），和「某人的博客」「这个作者发了什么」一起使用。
            返回前10条（标题/内容摘要/点赞数/评论数）。用户ID来自 queryUserProfile。
            """)
    public List<BlogBrief> queryUserBlogs(
            @ToolParam(description = "用户ID") Long userId) {
        Page<Blog> p = blogService.query().eq("user_id", userId).ne("images", "")
                .orderByDesc("create_time").page(new Page<>(1, SystemConstants.MAX_PAGE_SIZE));
        return p.getRecords().stream()
                .map(b -> new BlogBrief(b.getId(), b.getTitle(), TextUtils.truncate(b.getContent(), 80),
                        b.getLiked(), b.getComments(), null))
                .toList();
    }
}
