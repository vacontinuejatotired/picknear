package com.hmdp.content.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hmdp.content.entity.BlogComments;
import com.hmdp.content.mapper.BlogCommentsMapper;
import com.hmdp.content.service.IBlogCommentsService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 博客评论服务实现 — 评论查询（M-4 对齐：agent 工具改调业务方法）
 */
@Service
public class BlogCommentsServiceImpl extends ServiceImpl<BlogCommentsMapper, BlogComments> implements IBlogCommentsService {

    @Override
    public List<BlogComments> listByBlogIdOrderByCreateTime(Long blogId) {
        return list(new LambdaQueryWrapper<BlogComments>()
                .eq(BlogComments::getBlogId, blogId)
                .orderByAsc(BlogComments::getCreateTime));
    }
}
