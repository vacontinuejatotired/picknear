package com.hmdp.agent.observability.support;

import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * 统一脱敏出口（架构文档 §6.1）。
 * <p>
 * 所有写入 span 的属性必须经本类处理：脱敏（手机号/邮箱/身份证）+ 截断
 * （摘要类 200 字符 / 诊断类 4KB）。公网演示定位下，脱敏是上传第三方云的
 * 硬性要求——截断≠脱敏，手机号/正文/SQL 若不过滤会进 Langfuse。
 * </p>
 * <p>
 * 扩展：后续可按需将固定规则改为可插拔策略链（新增脱敏规则不改核心）。
 * </p>
 */
@Component
public class AttributeSanitizer {

    /** 摘要类属性上限（架构文档 §6.1：摘要类截断 200 字符） */
    private static final int SUMMARY_MAX_CHARS = 200;

    /** 诊断类属性上限（plan_json、guard 投票等，独立上限 4KB） */
    private static final int DIAGNOSTIC_MAX_CHARS = 4096;

    /** 大陆手机号：1[3-9]xxxxxxxxx → 138****8000 */
    private static final Pattern PHONE = Pattern.compile("(?<=1[3-9])\\d{4}(?=\\d{4})");

    /** 邮箱：保留首字符与 @ 域名 → a***@domain.com */
    private static final Pattern EMAIL_LOCAL = Pattern.compile("(?<=^[^@]{1})[^@]{3,}(?=@)");

    /** 身份证：18 位（末位可为 X）→ 前 6 后 4 */
    private static final Pattern ID_CARD = Pattern.compile("(?<=^\\d{6})\\d{8}(?=\\d{3}[0-9Xx]$)");

    /**
     * 摘要类属性：脱敏 + 截断 200 字符。
     */
    public String sanitizeSummary(String value) {
        return truncate(mask(value), SUMMARY_MAX_CHARS);
    }

    /**
     * 诊断类属性：脱敏 + 截断 4KB。
     */
    public String sanitizeDiagnostic(String value) {
        return truncate(mask(value), DIAGNOSTIC_MAX_CHARS);
    }

    private String mask(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        String masked = PHONE.matcher(value).replaceAll("****");
        masked = EMAIL_LOCAL.matcher(masked).replaceAll("***");
        return ID_CARD.matcher(masked).replaceAll("********");
    }

    private String truncate(String value, int maxChars) {
        if (value == null || value.length() <= maxChars) {
            return value;
        }
        return value.substring(0, maxChars) + "…";
    }
}
