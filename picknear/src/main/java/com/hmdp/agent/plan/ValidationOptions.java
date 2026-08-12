package com.hmdp.agent.plan;

import java.util.Set;

/**
 * 校验选项。
 *
 * @param enforceTree  是否按意图树做工具归属校验（legacy 为 false）
 * @param fallbackNodes 声明意图为空时的兜底节点集（关键词命中节点）
 * @param userId        当前用户 ID（用于 self 占位符解析）
 */
public record ValidationOptions(boolean enforceTree, Set<String> fallbackNodes, Long userId) {

    public static ValidationOptions legacy(Long userId) {
        return new ValidationOptions(false, Set.of(), userId);
    }

    public static ValidationOptions tree(Set<String> fallbackNodes, Long userId) {
        return new ValidationOptions(true, fallbackNodes, userId);
    }
}
