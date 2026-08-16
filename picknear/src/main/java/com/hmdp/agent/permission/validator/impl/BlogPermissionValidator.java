package com.hmdp.agent.permission.validator.impl;

import com.hmdp.agent.permission.enums.DataAction;
import com.hmdp.agent.permission.validator.DataPermissionValidator;
import com.hmdp.content.entity.Blog;
import com.hmdp.content.mapper.BlogMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 博客资源权限校验器
 * <p>
 * 校验规则：目标博客的 {@code userId} 必须等于当前登录用户 ID。
 * </p>
 * <ul>
 *   <li>数据不存在 → 无权限（false）</li>
 *   <li>非本人创建 → 无权限（false）</li>
 *   <li>本人创建 → 有权限（true）</li>
 * </ul>
 * <p>P3-S4：IBlogService 拆分后改 BlogMapper 直查（权限校验需原始实体归属判断）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BlogPermissionValidator implements DataPermissionValidator {

    private final BlogMapper blogMapper;

    @Override
    public String getResourceType() {
        return "blog";
    }

    @Override
    public String getResourceLabel() {
        return "博客";
    }

    @Override
    public boolean validate(Long userId, Object targetId, DataAction action) {
        if (targetId == null) {
            log.warn("博客权限校验失败：targetId 为空 [userId={}]", userId);
            return false;
        }

        // 兼容 Long / Integer，统一转为 Long
        Long blogId;
        if (targetId instanceof Number) {
            blogId = ((Number) targetId).longValue();
        } else {
            log.warn("博客权限校验失败：targetId 类型不支持 [type={}]", targetId.getClass());
            return false;
        }

        Blog blog = blogMapper.selectById(blogId);
        if (blog == null) {
            log.warn("博客权限校验失败：博客不存在 [userId={}, blogId={}]", userId, blogId);
            return false;
        }

        boolean hasPermission = userId.equals(blog.getUserId());
        if (!hasPermission) {
            log.warn("博客权限校验失败：越权操作 [userId={}, blogId={}, ownerId={}, action={}]",
                    userId, blogId, blog.getUserId(), action);
        }
        return hasPermission;
    }
}
