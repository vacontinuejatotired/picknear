package com.hmdp.agent.context;

/**
 * {@link AgentContext} 的 ThreadLocal 载体。
 * <p>
 * 使用约定：
 * <ul>
 *   <li><b>同步段</b>：请求入口创建后 {@link #set}，方法 finally 中 {@link #clear}（与根 span 清理同点）；</li>
 *   <li><b>异步边界</b>：由 {@link AgentContextPropagator}（TaskDecorator）自动捕获/恢复/清理，
 *       业务代码无需手动处理——这是机制承诺，异步线程里 {@link #get} 直接可读。</li>
 * </ul>
 * </p>
 */
public final class AgentContextHolder {

    private static final ThreadLocal<AgentContext> TL = new ThreadLocal<>();

    private AgentContextHolder() {
    }

    public static void set(AgentContext ctx) {
        TL.set(ctx);
    }

    public static AgentContext get() {
        return TL.get();
    }

    /**
     * 必填读取：缺失抛异常（Fail-Fast，防静默 NPE）。
     * <p>
     * 异步传播是机制承诺（TaskDecorator 已装配），读不到说明上下文链路有 bug
     * （入口未创建 / 线程池未装配 / 清理遗漏），显式抛错比返回 null 后深处 NPE 好定位。
     * </p>
     */
    public static AgentContext require() {
        AgentContext ctx = TL.get();
        if (ctx == null) {
            throw new IllegalStateException(
                    "AgentContext 未初始化（请求入口未创建 AgentContext，或异步边界未传播）");
        }
        return ctx;
    }

    public static void clear() {
        TL.remove();
    }
}
