package com.hmdp.agent.routing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.agent.guard.GuardedToolCallback;
import com.hmdp.agent.task.TaskReport;
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
     * 按需过滤关键词（toolName → 触发词）：用户输入命中任一词才把该工具列入紧凑目录，
     * 减少 planner 的候选工具数（省 token + 降低误选）。新增工具时须在此登记，
     * 未登记的工具保守放行（不全过滤，防漏）。
     */
    private static final Map<String, List<String>> TRIGGER_KEYWORDS = Map.ofEntries(
            Map.entry("queryPublishedBlogs", List.of("我的博客", "我发的", "看看博客", "浏览博客", "查看博客", "能看什么", "看博客")),
            Map.entry("publishTestBlog", List.of("发博客", "写博客", "发布", "发一篇", "测试博客")),
            Map.entry("queryBlogsByTitle", List.of("找博客", "搜博客", "搜索博客", "找一篇", "查一下关于", "有没有博客")),
            Map.entry("queryTotalBlogs", List.of("统计博客", "博客总数", "博客数量", "多少篇博客", "多少博客", "查一下博客数量")),
            Map.entry("queryTotalUsers", List.of("统计用户", "用户总数", "用户数量", "多少用户", "多少人注册", "查一下用户数")),
            Map.entry("queryTotalShops", List.of("统计店铺", "店铺总数", "店铺数量", "多少店铺", "商铺", "查一下店铺")),
            Map.entry("queryWeather", List.of("天气", "气温", "温度", "冷不冷", "热不热", "下雨", "晴天", "冷吗", "热吗")),
            Map.entry("queryShopTypes", List.of("店铺类型", "分类", "有哪些类型", "类型列表", "美食", "酒店", "影院")),
            Map.entry("queryShopsByType", List.of("类型的店铺", "美食店", "酒店有哪些", "按类型", "找店", "店铺列表", "有哪些店")),
            Map.entry("queryShopById", List.of("店铺详情", "这家店", "店铺怎么样", "店怎么样", "店铺信息")),
            Map.entry("queryVouchersByShop", List.of("优惠券", "有什么券", "领券", "抢券", "券")),
            Map.entry("queryMyVoucherOrders", List.of("我的订单", "我买的券", "下单记录", "订单")),
            Map.entry("queryBlogById", List.of("博客详情", "这篇博客", "看看这篇", "博客信息")),
            Map.entry("queryBlogComments", List.of("评论", "看看评论", "回复")),
            Map.entry("queryUserBlogs", List.of("某人的博客", "这个作者的博客", "他发的", "她发的", "的博客")),
            Map.entry("queryUserProfile", List.of("资料", "作者是谁", "用户信息", "个人信息", "是谁")),
            Map.entry("queryMyFollows", List.of("我的关注", "我关注了谁", "关注列表"))
    );

    private final ObjectMapper json;

    public CompactCatalogBuilder(ObjectMapper json) {
        this.json = json;
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
        List<String> keywords = TRIGGER_KEYWORDS.get(toolName);
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
