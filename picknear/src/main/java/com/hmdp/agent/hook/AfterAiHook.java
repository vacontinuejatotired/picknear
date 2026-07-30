package com.hmdp.agent.hook;

/**
 * AI 回复后的后处理 Hook。
 * <p>
 * 在 LLM 返回结果后执行，做轻量级判断（关键词匹配、回复长度校验、拒绝检测等），
 * 返回 {@link HookResult.Decision#PLANNING} 表示需要进入 {@code TaskPlanner} 拆解子任务。
 * </p>
 *
 * <pre>
 * 设计约束：
 * - 只做判断，不执行任务、不调 TaskPlanner
 * - 实现类不超过 15 行
 * - 多个 Hook 通过 {@link AfterAiHookChain} 聚合决策
 * </pre>
 *
 * @see AfterAiHookChain
 * @see HookResult
 */
@FunctionalInterface
public interface AfterAiHook {

    /**
     * LLM 回复后的后处理判断。
     *
     * @param originalInput 原始用户输入
     * @param aiResponse    LLM 回复内容
     * @param context       对话上下文
     * @return PASS: 无事发生 | PLANNING: 需要进入任务规划 | REPLACE: 替换回复 | BLOCK: 阻断
     */
    HookResult afterAi(String originalInput, String aiResponse, ChatContext context);
}
