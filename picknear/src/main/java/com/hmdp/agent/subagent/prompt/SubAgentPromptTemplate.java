package com.hmdp.agent.subagent.prompt;

/**
 * @deprecated 请使用 {@link TemplateConstants}。
 * 本类保留仅为编译兼容，将在下一批次删除。
 */
@Deprecated(forRemoval = true)
public final class SubAgentPromptTemplate {

    private SubAgentPromptTemplate() {}

    public static final String SNAPSHOT_BEGIN = TemplateConstants.SNAPSHOT_BEGIN;
    public static final String SNAPSHOT_END = TemplateConstants.SNAPSHOT_END;
    public static final int RAW_DATA_MAX_LENGTH = TemplateConstants.RAW_DATA_MAX_LENGTH;
    public static final int CURRENT_RESPONSE_MAX_LENGTH = TemplateConstants.CURRENT_RESPONSE_MAX_LENGTH;
    public static final String TASK_DESC_FORMAT = TemplateConstants.TASK_DESC_FORMAT;
    public static final String PARAM_CONSTRAINT_FORMAT = TemplateConstants.PARAM_CONSTRAINT_FORMAT;
    public static final String PARAM_VALUE_FORMAT = TemplateConstants.PARAM_VALUE_FORMAT;
    public static final String HISTORY_SEPARATOR = TemplateConstants.HISTORY_SEPARATOR;
}
