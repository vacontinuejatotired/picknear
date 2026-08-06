package com.hmdp.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.hmdp.agent.prompt.PromptKeys;
import com.hmdp.agent.prompt.PromptService;
import com.hmdp.agent.prompt.config.PromptProperties;
import com.hmdp.agent.prompt.model.ResolvedToolPrompt;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Map;

/**
 * 外置工具描述提供者：从 PromptService（Langfuse → 内置）取每工具描述，
 * 重建 {@link ToolDefinition} 覆盖 description + inputSchema 里参数描述。
 * <p>
 * 运行时优先级：Langfuse → 内置资源文件 → {@code @Tool}/{@code @ToolParam} 注解（delegate 原始定义）。
 * provider 级 {@code resolvedCache}（TTL = prompt.cache-ttl）避免每请求 JSON parse，
 * Langfuse 改描述后最长 cache-ttl 生效。
 * </p>
 */
@Slf4j
@Component
public class ExternalizedToolDefinitionProvider implements ToolDefinitionProvider {

    private final PromptService promptService;
    private final PromptProperties props;
    private final Cache<String, ToolDefinition> resolvedCache;
    private final ObjectMapper json = new ObjectMapper();

    public ExternalizedToolDefinitionProvider(PromptService promptService, PromptProperties props) {
        this.promptService = promptService;
        this.props = props;
        this.resolvedCache = Caffeine.newBuilder().expireAfterWrite(props.getCacheTtl()).build();
    }

    @Override
    public ToolDefinition resolve(ToolCallback delegate) {
        ToolDefinition raw = delegate.getToolDefinition();
        if (raw == null) {
            return null;
        }
        String name = raw.name();
        ToolDefinition cached = resolvedCache.getIfPresent(name);
        if (cached != null) {
            return cached;
        }
        ToolDefinition resolved = promptService.renderTool(PromptKeys.tool(name), Map.of())
                .map(tp -> apply(raw, tp))
                .orElse(raw);
        resolvedCache.put(name, resolved);
        return resolved;
    }

    /** 重建 ToolDefinition：描述优先外置值，schema 仅在 params 非空时 patch */
    private ToolDefinition apply(ToolDefinition raw, ResolvedToolPrompt tp) {
        String description = StringUtils.hasText(tp.description()) ? tp.description() : raw.description();
        String schema = tp.params() == null || tp.params().isEmpty()
                ? raw.inputSchema()
                : patchSchema(raw.inputSchema(), tp.params());
        return ToolDefinition.builder()
                .name(raw.name())
                .description(description)
                .inputSchema(schema)
                .build();
    }

    /** 覆盖 inputSchema 里 properties.{param}.description；任何异常返回原 schema（Fail-Open） */
    private String patchSchema(String schemaJson, Map<String, String> paramDescs) {
        if (!StringUtils.hasText(schemaJson)) {
            return schemaJson;
        }
        try {
            JsonNode root = json.readTree(schemaJson);
            if (!(root instanceof ObjectNode object)) {
                return schemaJson;
            }
            JsonNode properties = object.get("properties");
            if (!(properties instanceof ObjectNode propsNode)) {
                return schemaJson;
            }
            boolean changed = false;
            for (Map.Entry<String, String> e : paramDescs.entrySet()) {
                JsonNode param = propsNode.get(e.getKey());
                if (param instanceof ObjectNode paramObj) {
                    paramObj.put("description", e.getValue());
                    changed = true;
                }
            }
            if (!changed) {
                return schemaJson;
            }
            return json.writeValueAsString(object);
        } catch (Exception e) {
            log.warn("[tool] inputSchema 参数描述覆盖失败，保留原 schema: {}", e.getMessage());
            return schemaJson;
        }
    }
}
