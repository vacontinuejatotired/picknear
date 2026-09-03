package com.hmdp.agent.history.pressure;

/**
 * token 估算端口（策略接口，实现可替换）。只用于压缩触发与预算，不要求精确。
 */
public interface TokenEstimator {

    /** 估算一段文本的 token 数（确定性、单调、零网络）。 */
    int estimate(CharSequence text);
}