package com.hmdp.agent.honesty;

import com.hmdp.agent.context.AgentContext;
import com.hmdp.agent.hook.HookResult;
import com.hmdp.agent.hook.PromptHook;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 输入侧数据意图打标 Hook（反编造 L1）。
 * <p>
 * 基于原始输入（不可变）判定数据意图并写入 {@code AgentContext.attributes}（ATTR_DATA_INTENT），
 * 供 AfterAiHook（DataAssertionHook）与编排层（空计划兜底）消费。只打标不改输入（PASS）；
 * "无工具可查时的 REPLACE 引导"由 Phase1 模板纪律 + 空计划诚实兜底承载（P1 可再细化为按意图注入）。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataIntentPromptHook implements PromptHook {

    private final DataIntentClassifier classifier;

    @Override
    public HookResult beforePrompt(String originalInput, String currentInput, AgentContext context) {
        if (context == null) {
            return HookResult.pass();
        }
        DataIntent intent = classifier.classify(originalInput);
        if (intent.isDataQuery()) {
            context.putAttribute(HonestyKeys.ATTR_DATA_INTENT, intent);
            log.info("[DataIntent] 数据意图打标 [intent={}] input={}", intent, originalInput);
        }
        return HookResult.pass();
    }
}
