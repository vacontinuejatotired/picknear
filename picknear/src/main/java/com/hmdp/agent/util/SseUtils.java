package com.hmdp.agent.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * SSE 工具方法 + JSON 事件构建器。
 * <p>
 * 所有 SSE 推送的 JSON 事件统一用 ObjectMapper 序列化，
 * 不再手工拼接字符串，杜绝转义遗漏。
 * </p>
 */
@Slf4j
public final class SseUtils {

    private static final ObjectMapper JSON = new ObjectMapper();

    private SseUtils() {}

    // ════════════════════════════════════════════════════════════
    // JSON 事件构建
    // ════════════════════════════════════════════════════════════

    /** 错误事件：{"type":"error","error":"...","code":5001} */
    public static String errorEvent(String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "error");
        m.put("error", message != null ? message : "");
        m.put("code", 5001);
        return toJson(m);
    }

    /** 元事件：{"type":"meta","conversationId":"..."} */
    public static String metaEvent(String conversationId) {
        return toJson(Map.of("type", "meta", "conversationId", conversationId));
    }

    /** 进度事件（普通文本）：{"type":"progress","stage":"...","text":"..."} */
    public static String progressEvent(String stage, String text) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "progress");
        m.put("stage", stage);
        m.put("text", text);
        return toJson(m);
    }

    /** 进度事件（工具步骤）：{"type":"progress","stage":"step","toolName":"...","status":"..."} */
    public static String stepEvent(String toolName, String status) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "progress");
        m.put("stage", SseEventConstants.STAGE_STEP);
        m.put("toolName", toolName);
        m.put("status", status);
        return toJson(m);
    }

    /** 进度事件（确认）：{"type":"progress","stage":"confirm","text":"..."} */
    public static String confirmEvent(String text) {
        return progressEvent(SseEventConstants.STAGE_CONFIRM, text);
    }

    /** 对传入字符串做 JSON 字符串值转义（兼容老用法） */
    public static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    /**
     * 用 ObjectMapper 将 Map 序列化为 JSON 字符串。
     * 失败时降级到手工 escapeJson（保证不抛异常）。
     */
    public static String toJson(Map<String, Object> map) {
        try {
            return JSON.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            log.warn("JSON 序列化失败，降级到手工拼接", e);
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (var entry : map.entrySet()) {
                if (!first) sb.append(",");
                first = false;
                sb.append("\"").append(escapeJson(entry.getKey())).append("\":");
                Object v = entry.getValue();
                if (v instanceof String s) {
                    sb.append("\"").append(escapeJson(s)).append("\"");
                } else {
                    sb.append(v);
                }
            }
            sb.append("}");
            return sb.toString();
        }
    }

    // ════════════════════════════════════════════════════════════
    // SSE 推送封装
    // ════════════════════════════════════════════════════════════

    /** 安全推送原始文本 */
    public static void safeSend(SseEmitter emitter, String data) {
        try {
            emitter.send(SseEmitter.event().data(data));
        } catch (IOException e) {
            log.warn("SSE 推送失败，连接可能已关闭");
        } catch (IllegalStateException e) {
            // 断链修复（2026-08-04）：emitter 已 complete/超时后再 send 抛的是 ISE（非 IOException），
            // 静默忽略（终态后推送无意义），避免业务误判"流式失败"触发无意义 LLM 重试
        }
    }

    /** 安全推送 JSON 事件（自动序列化） */
    public static void safeSendJson(SseEmitter emitter, Map<String, Object> data) {
        safeSend(emitter, toJson(data));
    }
}
