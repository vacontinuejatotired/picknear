package com.hmdp.agent.plan.model;

import java.util.List;
import java.util.Map;

/**
 * 解析后的计划（wire format 契约）。
 *
 * @param declaredIntents LLM 声明的意图（可能为路径/节点名变体，尚未归一化；旧数组格式为空）
 * @param entries         工具条目列表 [{tool, params}, ...]
 */
public record ParsedPlan(List<String> declaredIntents, List<Map<String, Object>> entries) {

    public static ParsedPlan empty() {
        return new ParsedPlan(List.of(), List.of());
    }  
}
