package com.hmdp.agent.observability.support;

/**
 * 观测能力覆盖开关三态（评审 13.2.2：每个能力配用户级覆盖）。
 * <p>
 * 语义：{@code auto}（默认）= 跟随观测后端能力推导；{@code enabled}/{@code disabled} = 强制
 * 开启/关闭（调试/口径迁移用，如同后端内切换语义编码避免新旧数据口径不一致）。
 * </p>
 */
public enum TriState {

    /** 跟随后端能力推导 */
    AUTO,
    /** 强制开启 */
    ENABLED,
    /** 强制关闭 */
    DISABLED;

    /** 解析：auto 取后端能力推导值，enabled/disabled 为强制值 */
    public boolean resolve(boolean autoValue) {
        return switch (this) {
            case AUTO -> autoValue;
            case ENABLED -> true;
            case DISABLED -> false;
        };
    }

    /**
     * 从配置字符串解析：接受 {@code auto}/{@code true}/{@code false}（大小写不敏感）。
     * 无法识别时返回 {@link #AUTO}（Fail-Safe：跟能力走，不擅自收窄）。
     */
    public static TriState fromString(String value) {
        if (value == null) {
            return AUTO;
        }
        return switch (value.trim().toLowerCase()) {
            case "true", "enabled", "on" -> ENABLED;
            case "false", "disabled", "off" -> DISABLED;
            default -> AUTO;
        };
    }
}