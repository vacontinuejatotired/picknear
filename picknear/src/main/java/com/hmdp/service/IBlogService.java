package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.Blog;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
/**
 * 探店笔记服务接口 — 笔记CRUD、点赞、点赞用户列表、关注者Feed流
 */
public interface IBlogService extends IService<Blog> {

    Result queryById(Long id);

    Result queryHotById(Integer current);

    Result likeBlog(Long id);

    Result queryUserList(Long id);

    Result saveBlog(Blog blog);

    /** 更新博客图片列表 — 上传完成后调用，JSON 数组接收 */
    Result updateBlogImages(Long id, List<String> images);

    Result queryBlogOfFollow(Long max, Integer offset);

    /** 查询某个用户的所有笔记（分页），含作者信息和点赞状态 */
    Result queryByUserId(Long id, Integer current);

    /** 查询当前登录用户的笔记（分页），含作者信息和点赞状态 */
    Result queryMyBlog(Integer current);
}
