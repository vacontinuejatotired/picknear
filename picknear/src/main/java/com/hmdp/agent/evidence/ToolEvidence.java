package com.hmdp.agent.execution.model;

/**
 * 单次工具执行的真值证据（反编造 L0，{@link ExecutionOutput#getToolEvidence()} 元素）。
 * <p>
 * raw = 该工具经 guard 截断（现 maxResultChars=1200）后回灌进本轮上下文的文本，即"模型可见超集"；
 * 输出断言闸（L3）与事实账本（L4）以它（而非模型自转写的 rawResults）为锚。
 * 大结果（> 阈值）场景（P1 扩展）：raw 为 null，由 {@link #refId()} 指向 Redis 存档、{@link #gist()}
 * 为放上下文的压缩摘要。
 * </p>
 */
public record ToolEvidence(String toolName, String refId, String gist, String raw) {

    /** 短结果内联构造（P0）：raw 即真值，refId/gist 为空 */
    public static ToolEvidence inline(String toolName, String raw) {
        return new ToolEvidence(toolName, null, null, raw);
    }
}
