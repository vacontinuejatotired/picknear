package com.hmdp.agent.routing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.agent.guard.GuardedToolCallback;
import com.hmdp.agent.routing.CatalogBuilder;
import com.hmdp.agent.task.TaskReport;
import com.hmdp.agent.tool.ToolRegistry;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * 紧凑工具目录构建器（纯逻辑，可独立单测）。
 * <p>
 * 规划 prompt 用「工具名 + 首句短标签（参数名）」的紧凑目录替代全量描述，
 * 压缩 token 的同时保留路由所需的区分词（标签消歧）。参数名必拼——
 * 压缩丢参数描述后 LLM 会猜错 key，validatePlan 不校验 params 会直接运行时失败。
 * </p>
 */
@Component
public class CompactCatalogBuilder implements CatalogBuilder {

    private static final String NO_DESCRIPTION = "（无描述）";
    private static final String ELLIPSIS = "…";
    private static final int DEFAULT_MAX_TAG_LENGTH = 60;

    /**
     * 代码内 OVERRIDES：按 toolName 覆盖首句标签（工具元数据属代码，yml 只放行为开关）。
     * 仅在首句缺触发词/区分词时登记。
     */
    private static final Map<String, String> OVERRIDES = Map.of(
            "publishTestBlog", "发布一篇测试博客，用户说「发博客」「写博客」「发布」「发一篇」时使用",
            "queryPublishedBlogs", "查看/浏览当前用户自己的已发布博客，无需参数，用户说「我的博客」时使用"
    );

    /**
     * 按需过滤关键词（toolName → 触发词）由 {@link ToolRegistry} 提供：
     * 工具类上 {@code @ToolMeta(keywords=...)} 声明，用户输入命中任一词才把该工具列入紧凑目录，
     * 减少 planner 的候选工具数（省 token + 降低误选）。未登记关键词的工具保守放行（不全过滤，防漏）。
     */
    private final ObjectMapper json;
    private final ToolRegistry toolRegistry;

    public CompactCatalogBuilder(ObjectMapper json, ToolRegistry toolRegistry) {
        this.json = json;
        this.toolRegistry = toolRegistry;
    }

    /**
     * 构建紧凑目录：跳过 history 已完成/终失败工具，每工具一行
     * {@code - 工具名: 首句标签（参数：city, title）}。
     * 传入 {@code userInput} 时按 {@link #TRIGGER_KEYWORDS} 只列相关工具；
     * 一个都没匹配上（需求模糊）则回退全量紧凑目录，保证 planner 始终有候选。
     */
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

    /** 用户输入命中该工具任一触发词 → 相关；无输入/未登记关键词 → 保守放行 */
    private boolean isRelevant(String toolName, String userInput) {
        if (userInput == null || userInput.isBlank()) return true;
        List<String> keywords = toolRegistry.keywordsOf(toolName);
        if (keywords == null || keywords.isEmpty()) return true;
        for (String kw : keywords) {
            if (userInput.contains(kw)) return true;
        }
        return false;
    }

    /**
     * 短标签：OVERRIDES 优先 → 原始描述首句压空白 →（无描述）；
     * 追加参数名；超 maxTagLength 截断加 …。
     */
    String shortTag(ToolCallback cb, int maxTagLength) {
        String name = GuardedToolCallback.rawName(cb);
        String tag = OVERRIDES.getOrDefault(name, firstSentence(GuardedToolCallback.rawDescription(cb)));
        String paramNames = paramNames(GuardedToolCallback.getRawInputSchema(cb));
        String full = paramNames.isEmpty() ? tag : tag + "（参数：" + paramNames + "）";
        return truncate(full, maxTagLength > 0 ? maxTagLength : DEFAULT_MAX_TAG_LENGTH);
    }

    /** 原始描述首句：split("。",2)[0] 后压空白；空描述 →（无描述） */
    private static String firstSentence(String desc) {
        if (desc == null || desc.isBlank()) return NO_DESCRIPTION;
        return desc.split("。", 2)[0].replaceAll("\\s+", "");
    }

    /** 从 inputSchema 的 properties key 提取参数名，逗号连接；解析失败/无参数返回空串 */
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

    /** 超 maxTagLength 截断加 … */
    private static String truncate(String s, int max) {
        if (s == null || s.length() <= max) return s;
        return s.substring(0, max) + ELLIPSIS;
    }
}
