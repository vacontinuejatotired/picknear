package com.hmdp.agent.history.pressure;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HeuristicTokenEstimatorTest {

    private final HeuristicTokenEstimator estimator = new HeuristicTokenEstimator();

    @Test
    void should_estimate_cjk_and_ascii() {
        assertThat(estimator.estimate(null)).isZero();
        assertThat(estimator.estimate("")).isZero();
        // 4 个中文字 ≈ 4 token
        assertThat(estimator.estimate("你好世界")).isEqualTo(4);
        // 8 个 ascii 字符 ≈ 2 token（ceil）
        assertThat(estimator.estimate("abcdefgh")).isEqualTo(2);
    }
}