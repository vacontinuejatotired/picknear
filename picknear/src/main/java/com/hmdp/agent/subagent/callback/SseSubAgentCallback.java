package com.hmdp.agent.subagent.callback;

import com.hmdp.agent.stream.SseEventConstants;
import com.hmdp.agent.stream.SseUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * SSE 实现的进度回调。
 * <p>
 * 由 TaskPlanner 构造并传入 SubTaskExecution，
 * SubTaskAgent 通过接口方法推送进度，不直接依赖 SseEmitter。
 * </p>
 */
@RequiredArgsConstructor
public class SseSubAgentCallback implements SubAgentProgressCallback {

    private final SseEmitter emitter;

    @Override
    public void onExecuteStart(int taskCount) {
        SseUtils.safeSend(emitter, SseUtils.progressEvent(SseEventConstants.STAGE_EXECUTING,
                SseEventConstants.TEXT_EXECUTING_PREFIX + taskCount + SseEventConstants.TEXT_EXECUTING_SUFFIX));
    }

    @Override
    public void onMergeStart() {
        SseUtils.safeSend(emitter, SseUtils.progressEvent(SseEventConstants.STAGE_MERGING,
                SseEventConstants.TEXT_MERGING));
    }
}
