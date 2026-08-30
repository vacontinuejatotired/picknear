package com.hmdp.agent.model;

import lombok.Builder;
import lombok.Data;

import java.lang.reflect.Method;
import java.util.List;

/**
 * 工具元数据
 *
 * <p>描述一个工具的完整信息，包括名称、方法、返回类型、依赖关系、幂等性等。</p>
 * <p>由 {@link com.hmdp.agent.dag.plan.GraphAnalyzer} 在启动时扫描生成。</p>
 *
 * @author DAG Planning Executor
 * @version 2.0
 */
@Data
@Builder
public class ToolMetadata {

    /** 工具名称（@Tool 注解的 name 属性或方法名） */
    private String name;

    /** 工具方法 */
    private Method method;

    /** 返回类型 */
    private Class<?> returnType;

    /** 依赖的工具名称列表 */
    @Builder.Default
    private List<String> dependencies = List.of();

    /** 参数列表 */
    @Builder.Default
    private List<ParameterInfo> parameters = List.of();

    /** 是否标记为 SequentialOnly（禁止并行） */
    private boolean sequentialOnly;

    /** SequentialOnly 原因说明 */
    private String sequentialReason;

    // ==================== 异常重试相关 ====================

    /**
     * 操作是否幂等。
     * <p>
     * true = 幂等操作，异常时可安全重试（如查询、幂等写入）；
     * false = 非幂等操作，不应重试（如余额扣减、订单创建）。
     * </p>
     * <p>默认 true（可重试）。</p>
     */
    @Builder.Default
    private boolean idempotent = true;

    /**
     * 工具级最大重试次数。
     * <p>
     * -1 = 使用全局配置（{@code agent.subtask.dag.default-max-retries}）；
     * 0 = 禁止重试；
     * 正数 = 使用指定值。
     * </p>
     */
    @Builder.Default
    private int maxRetries = -1;

    /**
     * 超时时是否允许重试。
     * <p>
     * -1 = 跟随 idempotent 设置（幂等操作超时可重试，非幂等不可）；
     * 0 = 超时也不重试；
     * 1 = 超时可重试。
     * </p>
     */
    @Builder.Default
    private int retryOnTimeout = -1;

    // ==================== 便捷方法 ====================

    /**
     * 判断工具是否允许重试。
     *
     * @param isTimeout 是否为超时异常
     * @return true 表示允许重试
     */
    public boolean isRetryable(boolean isTimeout) {
        // 1. 工具明确禁止重试
        if (maxRetries == 0) {
            return false;
        }

        // 2. 超时场景：检查 retryOnTimeout 配置
        if (isTimeout) {
            if (retryOnTimeout == 0) {
                return false;  // 工具明确禁止超时重试
            }
            if (retryOnTimeout == 1) {
                return true;   // 工具明确允许超时重试
            }
            // retryOnTimeout == -1，跟随 idempotent
            return idempotent;
        }

        // 3. 非超时场景：根据幂等性判断
        return idempotent;
    }

    /**
     * 获取最大重试次数（工具级配置优先，否则使用默认值）。
     *
     * @param globalDefault 全局默认重试次数
     * @return 最大重试次数
     */
    public int getEffectiveMaxRetries(int globalDefault) {
        if (maxRetries >= 0) {
            return maxRetries;
        }
        return globalDefault;
    }
}
