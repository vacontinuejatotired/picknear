package com.hmdp.agent.honesty;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 数据意图分类器（反编造 L1，纯规则、不调 LLM）。
 * <p>
 * 从原始用户输入判定是否涉及平台数据查询。词表补齐现有 TaskTriggerHook 触发词的缺口
 * （多少/几家/总量/一共/销量/评分/排行/我的X/几号/星期几等），供输入侧强制路由。
 * 采用自维护关键词表（保守、宁缺勿滥）；词表对齐意图树 {@code ToolIntentTree.NODE_DEFS}，
 * 若后续工具集合变化在此增删类别词即可。
 * </p>
 */
@Slf4j
@Component
public class DataIntentClassifier {

    /** 类别判定优先级序（早的优先；每条命中即返回该类） */
    private static final List<Map.Entry<DataIntent, List<String>>> RULES = List.of(
            Map.entry(DataIntent.CLOCK, List.of("几号", "星期几", "今天星期", "现在几点", "几点钟", "几点了")),
            Map.entry(DataIntent.MY_ENTITY, List.of(
                    "我的博客", "我发的", "我发布的", "我关注", "我关注的", "我的订单", "我的券",
                    "我的优惠券", "我买过", "我买", "我下的单", "我的关注", "我写的")),
            Map.entry(DataIntent.PLATFORM_STATS, List.of(
                    "多少家", "多少用户", "多少人", "多少篇", "几条", "几个",
                    "几家", "共计", "一共", "总共有", "总共", "总量", "共有",
                    "总数", "店铺数", "用户数", "博客数", "平台数据", "销量", "人气",
                    "评分最高", "排名", "排行", "最多", "最高")),
            Map.entry(DataIntent.WEATHER, List.of("天气", "温度", "气温", "下雨", "晴天", "阴天", "下雨吗")),
            Map.entry(DataIntent.SHOP, List.of(
                    "店铺", "商家", "餐厅", "美食", "火锅", "咖啡", "景点", "门票", "门票",
                    "哪家店", "店怎么样", "这家店")),
            Map.entry(DataIntent.USER, List.of("作者是谁", "用户资料", "他是谁", "这个作者", "粉丝数", "个人资料")),
            Map.entry(DataIntent.VOUCHER, List.of("优惠券", "代金券", "秒杀券", "券订单", "订的券", "可用券", "有什么券")),
            Map.entry(DataIntent.BLOG, List.of("博客", "笔记", "点评", "帖子", "探店文", "发的文章"))
    );

    /**
     * 判定输入的数据意图。
     *
     * @param input 用户原始输入（空/空白返回 NONE）
     * @return 命中类别，否则 NONE
     */
    public DataIntent classify(String input) {
        if (input == null || input.isBlank()) {
            return DataIntent.NONE;
        }
        for (Map.Entry<DataIntent, List<String>> rule : RULES) {
            DataIntent intent = rule.getKey();
            for (String kw : rule.getValue()) {
                if (input.contains(kw)) {
                    log.debug("[DataIntent] 命中 {} 关键词=[{}] input={}", intent, kw, input);
                    return intent;
                }
            }
        }
        return DataIntent.NONE;
    }
}
