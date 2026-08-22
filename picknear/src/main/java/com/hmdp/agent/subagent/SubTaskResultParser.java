package com.hmdp.agent.subagent;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * @deprecated 请使用 {@link ResultParser}。
 * 本类保留仅为编译兼容，将在下一批次删除。
 */
@Deprecated(forRemoval = true)
public class SubTaskResultParser extends ResultParser {

    public SubTaskResultParser(ObjectMapper objectMapper) {
        super(objectMapper);
    }
}
