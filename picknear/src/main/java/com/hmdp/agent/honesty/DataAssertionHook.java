package com.hmdp.agent.honesty;

import com.hmdp.agent.context.AgentContext;
import com.hmdp.agent.hook.AfterAiHook;
import com.hmdp.agent.hook.HookResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 数据意图漏判兜底 Hook（反编造 L1）。
 * <p>
 * AfterAi 阶段：若上下文带数据意图标记 → 强制 PLANNING（不看 Phase1 是否已自答）。
 * 依据：Phase1 阶段结构上不绑任何工具，任何数据答复都未经核实，绝不应作为终答流出；
 * 必须进规划由工具取真值。与 TaskTriggerHook 的 PLANNING 传染聚合幂等兼容。
 * </p>
 * <p>
 * 触发时同步写 PLANNING seed 覆盖（ATTR_PLAN_SEED_OVERRIDE），避免 Phase1 若已输出的
 * 数字作为 currentResponse 污染规划/子 Agent（见 AiResponseRouter PLANNING 分支）。
 * </p>
 */
@Slf4j
@Component
public class DataAssertionHook implements AfterAiHook {

    @Override
    public HookResult afterAi(String originalInput, String aiResponse, AgentContext context) {
        if (context == null) {
            return HookResult.pass();
        }
        Object attr = context.attribute(HonestyKeys.ATTR_DATA_INTENT);
        if (!(attr instanceof DataIntent intent) || !intent.isDataQuery()) {
            return HookResult.pass();
        }
        context.putAttribute(HonestyKeys.ATTR_PLAN_SEED_OVERRIDE, HonestyKeys.PLAN_SEED_TEXT);
        log.info("[DataIntent] 数据意图强制规划 [intent={}]", intent);
        return HookResult.planningRequired();
    }
}
