package com.hmdp.controller;


import com.hmdp.dto.Result;
import com.hmdp.entity.Blog;
import com.hmdp.service.IBlogService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import jakarta.annotation.Resource;
import java.util.List;

/**
 * <p>
 * 探店笔记控制器 — 笔记CRUD、点赞、点赞列表、关注者Feed流
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@RestController
@RequestMapping("/blog")
@Tag(name = "探店笔记模块", description = "笔记发布、点赞、查询、Feed流等接口")
public class BlogController {

    @Resource
    private IBlogService blogService;

    @PostMapping
    @Operation(summary = "发布博客", description = "创建新的探店博客")
    public Result saveBlog(
            @Parameter(description = "博客内容") @RequestBody Blog blog) {
        return blogService.saveBlog(blog);
    }

    /**
     * 更新博客图片列表 — 上传完成后调用
     * 接收 JSON 数组而非逗号分隔字符串，避免 URL 含逗号时切分错误
     */
    @PutMapping("/{id}/images")
    @Operation(summary = "更新博客图片", description = "更新博客的图片列表")
    public Result updateBlogImages(
            @Parameter(description = "博客ID") @PathVariable("id") Long id,
            @Parameter(description = "图片URL列表") @RequestBody List<String> images) {
        return blogService.updateBlogImages(id, images);
    }

    @PutMapping("/like/{id}")
    @Operation(summary = "点赞博客", description = "给博客点赞或取消点赞")
    public Result likeBlog(
            @Parameter(description = "博客ID") @PathVariable("id") Long id) {
        // 修改点赞数量
//        blogService.update()
//                .setSql("liked = liked + 1").eq("id", id).update();
        return blogService.likeBlog(id);
    }

    @GetMapping("/of/user")
    @Operation(summary = "查询用户博客", description = "查询指定用户发布的博客列表")
    public Result queryBlogByUserId(
            @Parameter(description = "页码") @RequestParam(value = "current", defaultValue = "1") Integer current,
            @Parameter(description = "用户ID") @RequestParam("id") Long id) {
        return blogService.queryByUserId(id, current);
    }

    @GetMapping("/of/me")
    @Operation(summary = "查询我的博客", description = "查询当前用户发布的博客列表")
    public Result queryMyBlog(
            @Parameter(description = "页码") @RequestParam(value = "current", defaultValue = "1") Integer current) {
        return blogService.queryMyBlog(current);
    }

    @GetMapping("/hot")
    @Operation(summary = "查询热门博客", description = "查询热门推荐博客列表")
    public Result queryHotBlog(
            @Parameter(description = "页码") @RequestParam(value = "current", defaultValue = "1") Integer current) {
        return blogService.queryHotById(current);
    }

    @GetMapping("/{id}")
    @Operation(summary = "查询博客详情", description = "根据博客ID获取博客详细信息")
    public Result queryBlogById(
            @Parameter(description = "博客ID") @PathVariable("id") Long id) {
        return blogService.queryById(id);
    }

    @GetMapping("/likes/{id}")
    @Operation(summary = "查询点赞用户", description = "查询给博客点赞的用户列表")
    public Result queryLikeBlog(
            @Parameter(description = "博客ID") @PathVariable("id") Long id) {
        return blogService.queryUserList(id);
    }
    @GetMapping("/of/follow")
    @Operation(summary = "查询关注用户博客", description = "查询关注用户的博客Feed流")
    public Result queryBlogOfFollow(
            @Parameter(description = "最后一条博客ID") @RequestParam("lastId") Long max,
            @Parameter(description = "偏移量") @RequestParam(defaultValue  ="0",value ="offset")Integer offset) {
    return blogService.queryBlogOfFollow(max,offset);
    }
}

