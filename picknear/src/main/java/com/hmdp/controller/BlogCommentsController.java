package com.hmdp.controller;


import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.RequestMapping;

import org.springframework.web.bind.annotation.RestController;

/**
 * <p>
 * 博客评论控制器 — 预留，接口尚未实现（数据库表已存在 tb_blog_comments）
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@RestController
@RequestMapping("/blog-comments")
@Tag(name = "博客评论模块", description = "博客评论相关接口（开发中）")
public class BlogCommentsController {

}
