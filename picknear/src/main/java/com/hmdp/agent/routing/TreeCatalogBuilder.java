package com.hmdp.agent.routing;

import com.hmdp.agent.plan.routing.CatalogBuilder;

/**
 * @deprecated 请使用 {@link com.hmdp.agent.plan.routing.TreeCatalogBuilder}。
 * 本类保留仅为编译兼容，将在下一批次删除。
 */
@Deprecated(forRemoval = true)
public class TreeCatalogBuilder extends com.hmdp.agent.plan.routing.TreeCatalogBuilder implements CatalogBuilder {

    public TreeCatalogBuilder(com.hmdp.agent.plan.routing.CompactCatalogBuilder compactCatalogBuilder,
                              ToolIntentTree intentTree) {
        super(compactCatalogBuilder, intentTree);
    }
}
