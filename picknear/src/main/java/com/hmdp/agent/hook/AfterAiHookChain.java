package com.hmdp.agent.hook
;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * AfterAiHook 链式执行器。
 * <p>
 * 按优先级短路执行：<strong>BLOCK &gt; REPLACE &gt; PLANNING &gt; PASS</strong>
 * </p>
 *
 * <pre>
 * 规则：
 * - 任一 Hook 返回 BLOCK → 立即返回，忽略后续 Hook
 * - 任一 Hook 返回 REPLACE → 立即返回，覆盖后续所有判断
 * - 多个 Hook 返回 PLANNING → "传染性"聚合，只要有一个需要规划就进 TaskPlanner
 * - PASS 直接忽略
 * </pre>
 */
@Component
@Slf4j
public class AfterAiHookChain {

    private final List<AfterAiHook> hooks;

    public AfterAiHookChain(List<AfterAiHook> hooks) {
        this.hooks = hooks;
        log.info("AfterAiHookChain 初始化，注册 {} 个 Hook", hooks.size());
    }

    /**
     * 执行链中所有 Hook，聚合出最终决策。
     *
     * @param input     原始用户输入（经过前置 Hook 处理后的版本）
     * @param response  LLM 回复内容
     * @param context   对话上下文
     * @return 聚合后的决策结果
     */
    public HookResult execute(String input, String response, ChatContext context) {
        boolean anyPlanning = false;

        for (AfterAiHook hook : hooks) {
            HookResult result;
            try {
                result = hook.afterAi(input, response, context);
            } catch (Exception e) {
                log.warn("AfterAiHook 异常 [hook={}]", hook.getClass().getSimpleName(), e);
                continue;
            }

            switch (result.getDecision()) {
                case BLOCK -> {
                    log.info("AfterAiHook 阻断 [hook={}, reason={}]",
                            hook.getClass().getSimpleName(), result.getReason());
                    return result;
                }
                case REPLACE -> {
                    log.info("AfterAiHook 替换回复 [hook={}]", hook.getClass().getSimpleName());
                    return result;
                }
                case PLANNING -> {
                    log.info("AfterAiHook 触发规划 [hook={}]", hook.getClass().getSimpleName());
                    anyPlanning = true;
                }
                // PASS 忽略
            }
        }

        // PLANNING 具有"传染性"：只要一个 Hook 认为需要拆，就必须进 TaskPlanner
        if (anyPlanning) {
            return HookResult.planningRequired();
        }
        return HookResult.pass();
    }
}
