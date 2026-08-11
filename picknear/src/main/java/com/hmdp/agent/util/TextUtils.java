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
}
