package com.hmdp.agent.subagent;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * @deprecated 请使用 {@link com.hmdp.agent.execution.ResultParser}。
 * 本类保留仅为编译兼容，将在下一批次删除。
 */
@Deprecated(forRemoval = true)
public class ResultParser extends com.hmdp.agent.execution.ResultParser {

    public ResultParser(ObjectMapper objectMapper) {
        super(objectMapper);
    }
}
