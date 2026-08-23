package com.hmdp.agent.routing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.agent.plan.routing.CatalogBuilder;
import com.hmdp.agent.tool.ToolRegistry;

/**
 * @deprecated 请使用 {@link com.hmdp.agent.plan.routing.CompactCatalogBuilder}。
 * 本类保留仅为编译兼容，将在下一批次删除。
 */
@Deprecated(forRemoval = true)
public class CompactCatalogBuilder extends com.hmdp.agent.plan.routing.CompactCatalogBuilder implements CatalogBuilder {

    public CompactCatalogBuilder(ObjectMapper json, ToolRegistry toolRegistry) {
        super(json, toolRegistry);
    }
}
