package com.hmdp.agent.routing;

import com.hmdp.agent.guard.GuardedToolCallback;
import com.hmdp.agent.routing.CatalogBuilder;
import com.hmdp.agent.plan.model.TaskReport;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * 意图树剪枝目录构建器（两级路由用）。
 * <p>
 * 按 {@link ToolIntentTree#matchNodes} 命中的业务节点渲染剪枝后的树：
 * 顶层分节（查询/写操作）→ 业务节点 → 工具叶子（复用 {@link CompactCatalogBuilder#shortTag}）。
 * 跨组工具（如 queryVouchersByShop）在所属节点各列一次（刻意不去重）。
 * 无命中节点返回空串（"空命中"信号 → 规划器跳过 LLM 调用，不回归全量目录）。
 * </p>
 */
@Slf4j
@Component
public class TreeCatalogBuilder implements CatalogBuilder {

    private final CompactCatalogBuilder compactCatalogBuilder;
    private final ToolIntentTree intentTree;

    public TreeCatalogBuilder(CompactCatalogBuilder compactCatalogBuilder, ToolIntentTree intentTree) {
        this.compactCatalogBuilder = compactCatalogBuilder;
        this.intentTree = intentTree;
    }

    @Override
    public String build(ToolCallback[] callbacks, TaskReport history, int maxTagLength, String userInput) {
        Set<String> matched = intentTree.matchNodes(userInput);
        if (matched.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String top : List.of(ToolIntentTree.READ, ToolIntentTree.WRITE)) {
            boolean topStarted = false;
            for (ToolIntentTree.GroupNode node : intentTree.nodesFor(top)) {
                if (!matched.contains(node.id())) {
                    continue;
                }
                if (!topStarted) {
                    sb.append("【").append(ToolIntentTree.topName(top)).append("】\n");
                    topStarted = true;
                }
                sb.append("  【").append(node.display()).append("】\n");
                for (String toolName : node.tools()) {
                    ToolCallback cb = findByName(callbacks, toolName);
                    if (cb == null) {
                        continue;
                    }
                    if (history.isCompleted(toolName) || history.isFinalFailed(toolName)) {
                        continue;
                    }
                    sb.append("    - ").append(toolName).append(": ")
                            .append(compactCatalogBuilder.shortTag(cb, maxTagLength)).append("\n");
                }
            }
        }
        return sb.toString();
    }

    private static ToolCallback findByName(ToolCallback[] callbacks, String name) {
        if (callbacks == null) {
            return null;
        }
        for (ToolCallback cb : callbacks) {
            if (name.equals(GuardedToolCallback.rawName(cb))) {
                return cb;
            }
        }
        return null;
    }
}
