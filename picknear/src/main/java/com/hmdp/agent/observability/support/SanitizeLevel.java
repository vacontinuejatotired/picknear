package com.hmdp.agent.observability.support;

/**
 * 上报属性的脱敏级别，绑定 {@link AttributeSanitizer} 的对应处理方式。
 * <p>
 * 字段注册表 {@link com.hmdp.agent.observability.model.AgentField} 中每个字段声明自己的级别，
 * 调用点无需再选脱敏方法（摘要类 200 字符 / 诊断类 4KB）。
 * </p>
 */
public enum SanitizeLevel {

    /** 摘要类：脱敏 + 截断 200 字符（绝大多数业务字段） */
    SUMMARY,

    /** 诊断类：脱敏 + 截断 4KB（plan_json、投票明细等大字段） */
    DIAGNOSTIC;

    /**
     * 按级别分派到 sanitizer 对应方法。
     *
     * @param sanitizer 统一脱敏出口
     * @param value     原始值
     * @return 脱敏 + 截断后的值
     */
    public String sanitize(AttributeSanitizer sanitizer, String value) {
        return switch (this) {
            case SUMMARY -> sanitizer.sanitizeSummary(value);
            case DIAGNOSTIC -> sanitizer.sanitizeDiagnostic(value);
        };
    }
}
