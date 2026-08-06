package com.hmdp.agent.prompt.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 工具描述模板解析结果。
 * <p>
 * 内容约定为 JSON：{@code {"description":"...","params":{"参数名":"参数描述"}}}。
 * 解析失败（兼容纯文本模板）时把全文当 description、params 为空。
 * </p>
 *
 * @param description 工具描述（LLM 决定何时调用）
 * @param params      参数名 → 参数描述（可空，用于覆盖 inputSchema 里的描述）
 */
public record ResolvedToolPrompt(String description, Map<String, String> params) {

    private static final ObjectMapper JSON = new ObjectMapper();

    public static Optional<ResolvedToolPrompt> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            JsonNode root = JSON.readTree(raw);
            if (root.isObject() && root.hasNonNull("description")) {
                String desc = root.get("description").asText();
                Map<String, String> params = new LinkedHashMap<>();
                JsonNode p = root.path("params");
                if (p.isObject()) {
                    p.fields().forEachRemaining(e -> {
                        if (e.getValue().isTextual()) {
                            params.put(e.getKey(), e.getValue().asText());
                        }
                    });
                }
                return Optional.of(new ResolvedToolPrompt(desc, params));
            }
        } catch (Exception ignored) {
            // 非 JSON 模板 → 全文当描述
        }
        return Optional.of(new ResolvedToolPrompt(raw.trim(), Collections.emptyMap()));
    }
}
