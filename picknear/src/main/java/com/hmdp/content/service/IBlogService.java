package com.hmdp.content.service;

import com.hmdp.dto.Result;
import com.hmdp.content.entity.Blog;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;

/**
 * 探店笔记服务接口 — 发布域（P3-S3 拆分后）
 * <p>
 * 查询/点赞/Feed 已分别收敛至 BlogQueryService / BlogLikeService / feed 域；
 * 本接口仅剩发布（P3-S5 删除）。
 * </p>
 */
public interface IBlogService extends IService<Blog> {

    Result saveBlog(Blog blog);

    /** 更新博客图片列表 — 上传完成后调用，JSON 数组接收 */
    Result updateBlogImages(Long id, List<String> images);
}
