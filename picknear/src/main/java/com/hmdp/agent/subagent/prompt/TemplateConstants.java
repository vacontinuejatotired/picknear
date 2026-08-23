package com.hmdp.agent.subagent.prompt;

/**
 * @deprecated 请使用 {@link com.hmdp.agent.prompt.builder.TemplateConstants}。
 * 本类保留仅为编译兼容，将在下一批次删除。
 */
@Deprecated(forRemoval = true)
public final class TemplateConstants {

    private TemplateConstants() {}

    public static final String SNAPSHOT_BEGIN = com.hmdp.agent.prompt.builder.TemplateConstants.SNAPSHOT_BEGIN;
    public static final String SNAPSHOT_END = com.hmdp.agent.prompt.builder.TemplateConstants.SNAPSHOT_END;
    public static final int RAW_DATA_MAX_LENGTH = com.hmdp.agent.prompt.builder.TemplateConstants.RAW_DATA_MAX_LENGTH;
    public static final int CURRENT_RESPONSE_MAX_LENGTH = com.hmdp.agent.prompt.builder.TemplateConstants.CURRENT_RESPONSE_MAX_LENGTH;
    public static final String TASK_DESC_FORMAT = com.hmdp.agent.prompt.builder.TemplateConstants.TASK_DESC_FORMAT;
    public static final String PARAM_CONSTRAINT_FORMAT = com.hmdp.agent.prompt.builder.TemplateConstants.PARAM_CONSTRAINT_FORMAT;
    public static final String PARAM_VALUE_FORMAT = com.hmdp.agent.prompt.builder.TemplateConstants.PARAM_VALUE_FORMAT;
    public static final String HISTORY_SEPARATOR = com.hmdp.agent.prompt.builder.TemplateConstants.HISTORY_SEPARATOR;
}
