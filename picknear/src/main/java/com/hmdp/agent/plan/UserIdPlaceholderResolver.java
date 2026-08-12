package com.hmdp.agent.plan;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.Set;

/**
 * self 占位符解析（单一事实源）。
 * <p>
 * 规划 LLM 不知道当前用户 ID 时会编造 "self"/"me"/"我的" 等占位符传给有 userId 入参的工具，
 * 这里统一把明确的占位符替换为真实 userId。三层调用点共用：
 * ① PlanValidator（构建 SubTask 前）② GuardedToolCallback（执行前最后一层，覆盖快照恢复路径）。
 * </p>
 * <p>
 * 只替换明确占位符，不替换任意非数字串（避免把用户问的「张三的博客」误换成当前用户 ID）。
 * </p>
 */
@Slf4j
public final class UserIdPlaceholderResolver {

    private UserIdPlaceholderResolver() {}

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final Set<String> SELF_PLACEHOLDERS = Set.of(
            "self", "me", "mine", "my", "current", "current_user", "当前用户", "我", "自己", "我的");

    private static final Set<String> USER_ID_TOOLS = Set.of("queryUserBlogs", "queryUserProfile");

    /** 校验层：params 为 Map，就地替换 self 占位符为真实 userId（Long）。 */
    public static void resolveParams(Map<String, Object> params, String toolName, Long userId) {
        if (userId == null || params == null || !USER_ID_TOOLS.contains(toolName)) {
            return;
        }
        Object v = params.get("userId");
        if (v instanceof String s && isSelfPlaceholder(s.trim())) {
            log.info("[规划] 修正工具 {} 的 userId 占位符 {} -> {}", toolName, s, userId);
            params.put("userId", userId);
        }
    }

    /** 执行层：payload 为 JSON 字符串，解析→替换→序列化；解析失败原样返回（Fail-Open）。 */
    public static String resolvePayload(String payload, String toolName, Long userId) {
        if (payload == null || userId == null || !USER_ID_TOOLS.contains(toolName)) {
            return payload;
        }
        try {
            JsonNode root = JSON.readTree(payload);
            if (!(root instanceof ObjectNode obj)) {
                return payload;
            }
            JsonNode uid = obj.get("userId");
            if (uid != null && uid.isTextual() && isSelfPlaceholder(uid.asText().trim())) {
                obj.put("userId", userId);
                return JSON.writeValueAsString(obj);
            }
            return payload;
        } catch (Exception e) {
            log.warn("userId 占位符解析失败，保留原 payload [tool={}, err={}]", toolName, e.getMessage());
            return payload;
        }
    }

    public static boolean isSelfPlaceholder(String s) {
        if (s == null) {
            return false;
        }
        return SELF_PLACEHOLDERS.contains(s.toLowerCase());
    }
}
