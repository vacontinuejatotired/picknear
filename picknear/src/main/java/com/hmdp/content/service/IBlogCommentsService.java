package com.hmdp.content.service;

import com.hmdp.content.entity.BlogComments;
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
 * 博客评论服务接口
 */
public interface IBlogCommentsService extends IService<BlogComments> {

    /** 某篇博客的评论列表（按时间升序，agent 工具用，M-4 对齐） */
    List<BlogComments> listByBlogIdOrderByCreateTime(Long blogId);

}
