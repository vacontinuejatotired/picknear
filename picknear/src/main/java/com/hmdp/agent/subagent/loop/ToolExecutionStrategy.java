package com.hmdp.agent.subagent.loop;

/**
 * 工具调用循环策略接口（扩展点）。
 * <p>
 * 每种"工具调用想法"= 一个实现：
 * <ul>
 *   <li>{@link SerialToolLoop}：按轮逐个调用（默认）</li>
 *   <li>{@link BatchToolLoop}：一轮可发多个独立工具调用 + 轮内工具/压缩并发</li>
 * </ul>
 * 未来新想法（先并行独立工具→再依赖链、单次全量调用后一次总结等）= 新增实现类 + 一行配置。
 * </p>
 */
public interface ToolExecutionStrategy {

    /** 完整驱动一次工具循环，返回最终回复文本（循环异常/无法生成时可返回 null） */
    String execute(SubAgentToolLoopContext ctx);

    /** 该策略的 prompt 规则文本（注入 {@code {{toolCallRule}}} 占位符） */
    String toolCallRule();
}
