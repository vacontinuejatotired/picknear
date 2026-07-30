package com.hmdp.agent.permission.validator;

import com.hmdp.agent.permission.enums.DataAction;

/**
 * 数据权限校验器接口 — 策略模式
 * <p>
 * 每种资源类型（blog / user / order）各自实现此接口，
 * 通过 {@link PermissionValidatorFactory} 统一注册和路由。
 * </p>
 * <p>
 * 如需新增资源校验：
 * <ol>
 *   <li>创建 XxxPermissionValidator 实现本接口</li>
 *   <li>{@code @Component} 声明为 Spring Bean</li>
 *   <li>实现 {@link #validate} 方法 — 工厂启动时自动收集</li>
 * </ol>
 * 完全满足开闭原则（OCP），无需修改现有代码。
 * </p>
 */
public interface DataPermissionValidator {

    /**
     * 返回该校验器支持的资源类型标识
     * <p>与 {@code @RequiredDataPermission(resource = "...")} 中的值对应，如 "blog"、"user"。</p>
     *
     * @return 资源类型字符串（小写）
     */
    String getResourceType();

    /**
     * 执行数据归属权校验
     *
     * @param userId   当前登录用户 ID（从 ToolContext 中提取）
     * @param targetId 目标资源 ID（从方法参数提取统一转为 Long）
     * @param action   操作类型（READ / CREATE / UPDATE / DELETE）
     * @return true 表示有权限，false 表示无权限
     */
    boolean validate(Long userId, Object targetId, DataAction action);

    /**
     * 返回资源的中文名称（用于错误提示）
     * <p>如 "博客"、"用户"、"订单"。默认实现返回 resourceType，建议覆盖。</p>
     *
     * @return 中文资源名
     */
    default String getResourceLabel() {
        return getResourceType();
    }
}
