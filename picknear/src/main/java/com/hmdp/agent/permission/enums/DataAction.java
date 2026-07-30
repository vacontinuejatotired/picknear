package com.hmdp.agent.permission.enums;

/**
 * 数据操作动作枚举
 * 用于 @RequiresDataPermission 注解中指定操作类型
 */
public enum DataAction {

    /**
     * 查询/读取操作（低风险）
     */
    READ("READ", "查询", 1),

    /**
     * 新增/创建操作（中低风险）
     */
    CREATE("CREATE", "新增", 2),

    /**
     * 修改/更新操作（中风险）
     */
    UPDATE("UPDATE", "修改", 3),

    /**
     * 删除操作（高风险）
     */
    DELETE("DELETE", "删除", 4);

    /**
     * 枚举代码（与注解中的字符串对应）
     */
    private final String code;

    /**
     * 中文描述（用于日志和提示信息）
     */
    private final String description;

    /**
     * 风险等级（数值越大风险越高，用于分级审批）
     */
    private final int riskLevel;

    DataAction(String code, String description, int riskLevel) {
        this.code = code;
        this.description = description;
        this.riskLevel = riskLevel;
    }

    public String getCode() {
        return code;
    }

    public String getDescription() {
        return description;
    }

    public int getRiskLevel() {
        return riskLevel;
    }

    /**
     * 根据 code 获取枚举（用于 AOP 中从注解字符串反查）
     */
    public static DataAction fromCode(String code) {
        for (DataAction action : values()) {
            if (action.code.equalsIgnoreCase(code)) {
                return action;
            }
        }
        throw new IllegalArgumentException("未知的操作类型: " + code);
    }

    /**
     * 判断是否为高风险操作（需要二次确认）
     */
    public boolean isHighRisk() {
        return this.riskLevel >= 4;
    }

    /**
     * 判断是否为写操作（CREATE, UPDATE, DELETE）
     */
    public boolean isWriteOperation() {
        return this != READ;
    }

    @Override
    public String toString() {
        return description + "(" + code + ")";
    }
}