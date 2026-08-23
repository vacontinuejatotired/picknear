package com.hmdp.agent.plan.routing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.agent.guard.GuardedToolCallback;
import com.hmdp.agent.plan.model.TaskReport;
import com.hmdp.agent.tool.ToolRegistry;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * 紧凑工具目录构建器（纯逻辑，可独立单测）。
 * <p>
 * 规划 prompt 用「工具名 + 首句短标签（参数名）」的紧凑目录替代全量描述。
 * </p>
 */
@Component
public class CompactCatalogBuilder implements CatalogBuilder {

    private static final String NO_DESCRIPTION = "（无描述）";
    private static final String ELLIPSIS = "…";
    private static final int DEFAULT_MAX_TAG_LENGTH = 60;

    private static final Map<String, String> OVERRIDES = Map.of(
            "publishTestBlog", "发布一篇测试博客，用户说「发博客」「写博客」「发布」「发一篇」时使用",
            "queryPublishedBlogs", "查看/浏览当前用户自己的已发布博客，无需参数，用户说「我的博客」时使用"
    );

    private final ObjectMapper json;
    private final ToolRegistry toolRegistry;

    public CompactCatalogBuilder(ObjectMapper json, ToolRegistry toolRegistry) {
        this.json = json;
        this.toolRegistry = toolRegistry;
    }

    @Override
    public String build(ToolCallback[] callbacks, TaskReport history, int maxTagLength, String userInput) {
        StringBuilder sb = new StringBuilder();
        for (ToolCallback cb : callbacks) {
            String name = GuardedToolCallback.rawName(cb);
            if (history.isCompleted(name) || history.isFinalFailed(name)) continue;
            if (!isRelevant(name, userInput)) continue;
            sb.append("- ").append(name).append(": ").append(shortTag(cb, maxTagLength)).append("\n");
        }
        if (sb.length() == 0) {
            for (ToolCallback cb : callbacks) {
                String name = GuardedToolCallback.rawName(cb);
                if (history.isCompleted(name) || history.isFinalFailed(name)) continue;
                sb.append("- ").append(name).append(": ").append(shortTag(cb, maxTagLength)).append("\n");
            }
        }
        return sb.toString();
    }

    private boolean isRelevant(String toolName, String userInput) {
        if (userInput == null || userInput.isBlank()) return true;
        List<String> keywords = toolRegistry.keywordsOf(toolName);
        if (keywords == null || keywords.isEmpty()) return true;
        for (String kw : keywords) {
            if (userInput.contains(kw)) return true;
        }
        return false;
    }

    String shortTag(ToolCallback cb, int maxTagLength) {
        String name = GuardedToolCallback.rawName(cb);
        String tag = OVERRIDES.getOrDefault(name, firstSentence(GuardedToolCallback.rawDescription(cb)));
        String paramNames = paramNames(GuardedToolCallback.getRawInputSchema(cb));
        String full = paramNames.isEmpty() ? tag : tag + "（参数：" + paramNames + "）";
        return truncate(full, maxTagLength > 0 ? maxTagLength : DEFAULT_MAX_TAG_LENGTH);
    }

    private static String firstSentence(String desc) {
        if (desc == null || desc.isBlank()) return NO_DESCRIPTION;
        return desc.split("。", 2)[0].replaceAll("\\s+", "");
    }

    private String paramNames(String inputSchema) {
        if (inputSchema == null || inputSchema.isBlank()) return "";
        try {
            JsonNode root = json.readTree(inputSchema);
            JsonNode props = root.path("properties");
            if (!props.isObject()) return "";
            Iterator<String> names = props.fieldNames();
            StringBuilder sb = new StringBuilder();
            while (names.hasNext()) {
                if (sb.length() > 0) sb.append(", ");
                sb.append(names.next());
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static String truncate(String s, int max) {
        if (s == null || s.length() <= max) return s;
        return s.substring(0, max) + ELLIPSIS;
    }
}
