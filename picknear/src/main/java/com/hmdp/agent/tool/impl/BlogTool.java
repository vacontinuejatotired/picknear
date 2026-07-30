package com.hmdp.agent.tool.impl;

import java.util.List;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.agent.annotation.TargetTool;
import com.hmdp.agent.permission.annotation.RequiredDataPermission;
import com.hmdp.agent.permission.enums.DataAction;
import com.hmdp.entity.Blog;
import com.hmdp.service.IBlogService;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;

@TargetTool(active = true)
@Slf4j
public class BlogTool {

    @Resource
    private IBlogService blogService;

    /**
     * 查看/浏览/查询当前用户自己发布的博客列表。
     * 用户说"我的博客"、"我发了什么"、"看看博客"、"能看什么"、"浏览/显示数据"、"有什么内容"等时触发。
     * 按点赞数排序返回最多10条（标题+内容+点赞数+发布时间）。
     * @param toolContext Spring AI 上下文，自动注入当前 userId
     */
    @Tool(description = """
            查看/浏览/显示当前用户自己的已发布博客列表，"我的博客"、"看看/浏览"、"能看什么"时使用。
            按点赞数降序返回前10条（包含标题、内容、点赞数）。
            注意：只看当前用户自己发布的博客，不能看别人的。
            """)
    @RequiredDataPermission(resource  = "blog", action = DataAction.READ)
    public List<Blog> queryPublishedBlogs(ToolContext toolContext) {
        Long userId = (Long) toolContext.getContext().get("userId");

        log.info("queryPublishedBlogs userId: {}", userId);
        Page<Blog> page = blogService.query().eq("user_id", userId).orderByDesc("liked").page(new Page<>(1, 10));
        return page.getRecords();
    }

    /**
     * 为当前用户发布一篇测试博客（标题固定为"测试博客"）。
     * 用户说"发博客"、"写博客"、"发布测试"、"发一篇"时触发。
     * 注意：内容固定为测试数据，不适合发正式内容。
     * @param toolContext Spring AI 上下文，自动注入当前 userId
     */
    @Tool(description = """
            发布一篇测试博客，标题自动设为"测试博客"、内容固定。
            用户说"发博客"、"写博客"、"发布"、"发一篇"时使用。
            注意：只能发固定内容的测试数据，不支持自定义标题和正文。
            """)
    @RequiredDataPermission(resource  = "blog", action = DataAction.CREATE)
    public Blog publishTestBlog(ToolContext toolContext) {
        Long userId = (Long) toolContext.getContext().get("userId");
        log.info("publishTestBlog userId: {}", userId);
        Blog blog = new Blog();
        blog.setUserId(userId);
        blog.setTitle("测试博客");
        blog.setContent("这是一篇测试博客");
        boolean save = blogService.save(blog);
        if (!save) {
            log.error("publishTestBlog failed, blog: {}", blog);
            return null;
        }
        return blog;
    }

    /**
     * 按标题模糊搜索博客，返回标题包含关键词的所有博客（不限用户）。
     * 用户说「找博客」「搜索…」「查一下关于…」「有没有…的博客」时触发。
     * @param title 搜索关键词，支持模糊匹配（如「旅游」能搜到标题含「旅游」的博客）
     */
    @Tool(description = """
            按标题模糊搜索博客，和「找一篇关于…的博客」「搜索/查询博客」一起使用。
            返回标题包含关键词的所有博客（不限用户），适合批量查同主题文章。
            """)
    @RequiredDataPermission(resource  = "blog", action = DataAction.READ)
    public List<Blog> queryBlogsByTitle(@ToolParam(description = "搜索关键词，例如：旅游——会搜到标题含「旅游」的博客") String title) {
        return blogService.query().like("title", title).list();
    }
}
