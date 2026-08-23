package com.hmdp.agent.routing;

import com.hmdp.agent.plan.model.TaskReport;
import org.springframework.ai.tool.ToolCallback;

/**
 * 规划目录构建策略接口。
 * <p>
 * CompactCatalogBuilder（扁平紧凑目录）与 TreeCatalogBuilder（意图树剪枝目录）都实现此接口，
 * 由对应 PlanRouter 策略选用。
 * </p>
 */
public interface CatalogBuilder {

    /**
     * 构建目录文本。
     *
     * @return 目录文本；TreeCatalogBuilder 在无命中节点时返回空串（空命中信号）
     */
    String build(ToolCallback[] callbacks, TaskReport history, int maxTagLength, String userInput);
}
