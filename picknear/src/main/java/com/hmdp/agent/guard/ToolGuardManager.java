package com.hmdp.agent.guard;

import com.hmdp.agent.guard.GuardResult.Decision;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 工具守卫管理器 — 汇总所有 {@link ToolGuardPolicy} 的投票并做出最终决策
 * <p>
 * 决策规则：
 * <ol>
 *   <li>存在任意 {@link Vote#BLOCK} → 直接拦截</li>
 *   <li>无 BLOCK、存在任意 {@link Vote#CONFIRM} → 需要确认</li>
 *   <li>全部 {@link Vote#ABSTAIN} / {@link Vote#ALLOW} → 放行</li>
 * </ol>
 * </p>
 */
@Slf4j
@Component
public class ToolGuardManager {

    private final List<ToolGuardPolicy> policies;

    public ToolGuardManager(List<ToolGuardPolicy> policies) {
        this.policies = policies;
        log.info("ToolGuardManager 初始化，已注册 {} 个策略: {}",
                policies.size(),
                policies.stream().map(p -> p.policyName()).toList());
    }

    /**
     * 对一次工具调用执行全部策略评估
     *
     * @param context 工具调用上下文
     * @return 最终决策（ALLOW / BLOCK / CONFIRM）
     */
    public GuardResult evaluate(ToolInvocationContext context) {
        String blockReason = null;
        String blockPolicy = null;
        String confirmReason = null;
        String confirmPolicy = null;

        for (ToolGuardPolicy policy : policies) {
            Vote vote;
            try {
                vote = policy.vote(context);
            } catch (Exception e) {
                log.warn("策略 [{}] 评估异常，已跳过: {}", policy.policyName(), e.toString());
                continue;
            }

            switch (vote) {
                case BLOCK -> {
                    blockReason = "❌ 安全策略拦截：" + policy.policyName();
                    blockPolicy = policy.policyName();
                    log.warn("工具调用被 [{}] 拦截 [tool={}, userId={}]",
                            policy.policyName(), context.getToolName(), context.getUserId());
                }
                case CONFIRM -> {
                    if (confirmReason == null) {
                        confirmReason = "⚠️ 需要确认：" + policy.policyName();
                        confirmPolicy = policy.policyName();
                    }
                    log.info("工具调用需要确认 [{}] [tool={}, userId={}]",
                            policy.policyName(), context.getToolName(), context.getUserId());
                }
                case ALLOW -> log.debug("策略 [{}] 放行 [tool={}]", policy.policyName(), context.getToolName());
                case ABSTAIN -> {} // 弃权，不处理
            }

            // 一旦有 BLOCK 立即返回，无需继续评估
            if (blockReason != null) {
                return GuardResult.block(blockReason, blockPolicy);
            }
        }

        if (confirmReason != null) {
            return GuardResult.confirm(confirmReason, confirmPolicy);
        }

        return GuardResult.allow();
    }
}
