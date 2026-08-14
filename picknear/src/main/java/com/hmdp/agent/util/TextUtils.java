package com.hmdp.agent.util;

/**
 * 文本工具。
 * <p>
 * codepoint-safe 截断：按码点计数，避免 {@link String#substring} 在 emoji 等
 * 代理对上切出孤立代理对（写入请求 JSON 会变乱码）。
 * </p>
 */
public final class TextUtils {

    private TextUtils() {}

    /**
     * 截取前 {@code max} 个码点，超长追加 "..."；{@code null} 或 {@code max<=0} 原样返回。
     */
    public static String truncate(String s, int max) {
        if (s == null || max <= 0) return s;
        if (s.codePointCount(0, s.length()) <= max) return s;
        int end = s.offsetByCodePoints(0, max);
        return s.substring(0, end) + "...";
    }

    /**
     * 生成给用户的异常摘要：取根因消息首行并截断到 80 字符。
     * <p>
     * 完整堆栈只进日志，避免把内部 SQL/框架细节直接暴露给用户。
     * </p>
     */
    public static String errorSummary(Throwable t) {
        if (t == null) return "未知错误";
        Throwable root = t;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String msg = root.getMessage();
        if (msg == null || msg.isBlank()) {
            return root.getClass().getSimpleName();
        }
        String firstLine = msg.split("\n", 2)[0];
        return firstLine.length() > 80 ? firstLine.substring(0, 80) : firstLine;
    }
}
