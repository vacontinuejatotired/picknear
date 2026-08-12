package com.hmdp.agent.plan;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.hmdp.agent.util.TextUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * wire format 解析器。
 * <p>
 * 两级路由的规划 LLM 输出 {@code {intents:[...],plan:[{tool,params}]}} 对象；
 * 兼容旧数组格式 {@code [{tool,params}]}。提取阶段先找 {@code ===PLAN_START===/===PLAN_END===} 标记，
 * 无标记时从首个 {@code [} 或 {@code \{} 提取（带深度计数 + 字符串字面量跳过，支持嵌套）。
 * </p>
 */
@Slf4j
@Component
public class PlanParser {

    public static final String PLAN_START = "===PLAN_START===";
    public static final String PLAN_END = "===PLAN_END===";

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * 解析规划 LLM 原始回复。
     *
     * @return 解析后的计划；非法输入返回 {@link ParsedPlan#empty()}（Fail-Open）
     */
    public ParsedPlan parse(String rawPlanJson) {
        String json = extractPlanJson(rawPlanJson);
        try {
            JsonNode root = JSON.readTree(json);
            if (root.isObject()) {
                return new ParsedPlan(readIntents(root.get("intents")), readEntries(root.get("plan")));
            }
            if (root.isArray()) {
                return new ParsedPlan(List.of(), JSON.convertValue(root, new TypeReference<>() {}));
            }
            log.warn("  [规划] 结果非 JSON 数组/对象: {}", TextUtils.truncate(rawPlanJson, 100));
            return ParsedPlan.empty();
        } catch (JsonProcessingException e) {
            log.warn("规划结果 JSON 解析失败: {}", e.getMessage());
            return ParsedPlan.empty();
        }
    }

    private static List<String> readIntents(JsonNode intents) {
        if (intents == null || !intents.isArray()) {
            return List.of();
        }
        List<String> list = new ArrayList<>();
        for (JsonNode n : intents) {
            if (n.isTextual() && !n.asText().isBlank()) {
                list.add(n.asText().trim());
            }
        }
        return list;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> readEntries(JsonNode plan) {
        if (plan == null || !plan.isArray()) {
            return List.of();
        }
        return JSON.convertValue(plan, new TypeReference<List<Map<String, Object>>>() {});
    }

    /** 从原始文本提取 JSON：先找标记，否则取首个 [ 或 { 包裹的内容；提取不到返回 "[]" */
    private static String extractPlanJson(String raw) {
        if (raw == null || raw.isBlank()) {
            return "[]";
        }
        int startIdx = raw.indexOf(PLAN_START);
        if (startIdx >= 0) {
            startIdx += PLAN_START.length();
            int endIdx = raw.indexOf(PLAN_END, startIdx);
            if (endIdx >= 0) {
                return raw.substring(startIdx, endIdx).trim();
            }
            // 有开始标记但没结束标记（响应被截断）：从剩余文本尽量提取
            return extractJsonValue(raw.substring(startIdx));
        }
        return extractJsonValue(raw);
    }

    private static String extractJsonValue(String text) {
        if (text == null || text.isBlank()) {
            return "[]";
        }
        int bracket = text.indexOf('[');
        int brace = text.indexOf('{');
        // 先取靠前的括号：对象格式 {"intents":[...],...} 里首个 [ 在对象内部，必须优先 {
        if (brace >= 0 && (bracket < 0 || brace < bracket)) {
            int close = findMatchingClose(text, brace, '{', '}');
            if (close >= 0) {
                return text.substring(brace, close + 1).trim();
            }
        }
        if (bracket >= 0) {
            int close = findMatchingClose(text, bracket, '[', ']');
            if (close >= 0) {
                return text.substring(bracket, close + 1).trim();
            }
        }
        return "[]";
    }

    /** 深度计数的闭合匹配（支持嵌套），忽略字符串字面量内的括号；找不到返回 -1 */
    private static int findMatchingClose(String s, int open, char openChar, char closeChar) {
        if (open < 0) {
            return -1;
        }
        int depth = 1;
        boolean inString = false;
        for (int i = open + 1; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"') {
                if (i > 0 && s.charAt(i - 1) == '\\') {
                    continue;
                }
                inString = !inString;
                continue;
            }
            if (inString) {
                continue;
            }
            if (c == openChar) {
                depth++;
            } else if (c == closeChar) {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }
}
