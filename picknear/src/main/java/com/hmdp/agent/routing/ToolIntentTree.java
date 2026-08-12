package com.hmdp.agent.routing;

import lombok.extern.slf4j.Slf4j;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 意图树数据模型（三层次级：根 → 查询/写操作 → 业务类别 → 工具叶子）。
 * <p>
 * 纯静态表 + 纯函数，无 Spring 依赖，可独立单测。新增工具时在此登记节点归属。
 * </p>
 */
@Slf4j
public final class ToolIntentTree {

    private ToolIntentTree() {}

    /** 顶层类别 */
    public static final String READ = "read";
    public static final String WRITE = "write";

    /** 单个业务节点（叶子=工具；跨组工具可出现在多节点） */
    public record GroupNode(String id, String display, String top, List<String> keywords, List<String> tools) {}

    /** 顶层类别显示名 */
    public static String topName(String top) {
        return READ.equals(top) ? "查询" : "写操作";
    }

    /** 业务节点表（GROUP_ORDER 决定目录展示顺序，跨组工具刻意重复列出防漏选） */
    public static final List<GroupNode> NODES = List.of(
            new GroupNode("shop", "店铺", READ,
                    List.of("店铺", "店", "美食", "酒店", "影院", "分类", "类型"),
                    List.of("queryShopTypes", "queryShopsByType", "queryShopById", "queryVouchersByShop")),
            new GroupNode("blog", "博客", READ,
                    List.of("博客", "点评", "文章", "笔记", "帖子", "评论", "搜索", "找", "浏览", "看看", "作者"),
                    List.of("queryPublishedBlogs", "queryBlogsByTitle", "queryUserBlogs",
                            "queryBlogById", "queryBlogComments")),
            new GroupNode("user", "用户", READ,
                    List.of("用户", "资料", "个人信息", "关注", "粉丝", "作者是谁"),
                    List.of("queryUserProfile", "queryMyFollows")),
            new GroupNode("weather", "天气", READ,
                    List.of("天气", "气温", "温度", "下雨", "晴", "冷", "热"),
                    List.of("queryWeather")),
            new GroupNode("stats", "统计", READ,
                    List.of("统计", "总数", "数量", "多少篇", "多少人", "多少店铺"),
                    List.of("queryTotalBlogs", "queryTotalShops", "queryTotalUsers")),
            new GroupNode("voucher", "优惠券", READ,
                    List.of("优惠券", "券", "领券", "抢券", "订单", "下单", "秒杀", "买的"),
                    List.of("queryMyVoucherOrders", "queryVouchersByShop")),
            new GroupNode("publish", "发布", WRITE,
                    List.of("发布", "发博客", "写博客", "发一篇", "发个"),
                    List.of("publishTestBlog"))
    );

    /** 展示顺序（进 prompt 必须稳定，单测依赖） */
    public static final List<String> GROUP_ORDER = NODES.stream().map(GroupNode::id).toList();

    /**
     * 按用户输入命中业务节点（多组并集）。
     * <p>
     * 空白输入 → 全部节点（放行兜底）；非空白但一个关键词都没命中 → 空集（目录为空 → 跳过规划调用）。
     * </p>
     */
    public static Set<String> matchNodes(String userInput) {
        if (userInput == null || userInput.isBlank()) {
            return NODES.stream().map(GroupNode::id).collect(Collectors.toCollection(LinkedHashSet::new));
        }
        Set<String> matched = new LinkedHashSet<>();
        for (GroupNode node : NODES) {
            for (String kw : node.keywords()) {
                if (userInput.contains(kw)) {
                    matched.add(node.id());
                    break;
                }
            }
        }
        return matched;
    }

    /**
     * 归一化 LLM 声明的意图 → 节点 id 集合。
     * <p>
     * 接受完整路径（"查询→博客"）、节点 id（"blog"）、显示名（"博客"）；识别不了丢弃 + WARN。
     * </p>
     */
    public static Set<String> resolveIntents(List<String> rawIntents) {
        Set<String> resolved = new LinkedHashSet<>();
        if (rawIntents == null) {
            return resolved;
        }
        for (String raw : rawIntents) {
            String nodeId = normalizeIntent(raw);
            if (nodeId != null) {
                resolved.add(nodeId);
            } else {
                log.warn("  [规划] 无法识别的意图声明: {}", raw);
            }
        }
        return resolved;
    }

    /** 单个意图文本 → 节点 id；不识别的路径/名称返回 null */
    private static String normalizeIntent(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String s = raw.trim();
        // 完整路径 "查询→博客" 取末段
        if (s.contains("→")) {
            s = s.substring(s.lastIndexOf('→') + 1).trim();
        }
        String lower = s.toLowerCase(Locale.ROOT);
        // 节点 id 直接命中
        for (GroupNode node : NODES) {
            if (node.id().equals(lower)) {
                return node.id();
            }
        }
        // 显示名命中
        for (GroupNode node : NODES) {
            if (node.display().equals(s)) {
                return node.id();
            }
        }
        return null;
    }

    /** 工具是否属于给定节点集合中的任意一个（跨组判定） */
    public static boolean toolIn(String toolName, Collection<String> nodeIds) {
        if (nodeIds == null || nodeIds.isEmpty()) {
            return false;
        }
        return !nodesOf(toolName).isEmpty() && nodesOf(toolName).stream().anyMatch(nodeIds::contains);
    }

    /** 工具所属节点（逆索引；跨组工具返回多节点） */
    public static Set<String> nodesOf(String toolName) {
        Set<String> result = new LinkedHashSet<>();
        for (GroupNode node : NODES) {
            if (node.tools().contains(toolName)) {
                result.add(node.id());
            }
        }
        return result;
    }

    /** WRITE 子树的所有工具（写操作审批一致性校验用） */
    public static Set<String> writeTools() {
        Set<String> result = new LinkedHashSet<>();
        for (GroupNode node : NODES) {
            if (WRITE.equals(node.top())) {
                result.addAll(node.tools());
            }
        }
        return result;
    }

    /** 指定顶层类别的有序节点列表（目录渲染用） */
    public static List<GroupNode> nodesFor(String top) {
        return NODES.stream()
                .filter(n -> top.equals(n.top()))
                .toList();
    }
}
