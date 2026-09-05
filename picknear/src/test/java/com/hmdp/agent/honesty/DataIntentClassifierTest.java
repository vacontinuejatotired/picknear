package com.hmdp.agent.honesty;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DataIntentClassifier — 词表命中单测（正例命中类别、反例返回 NONE）。
 */
class DataIntentClassifierTest {

    private final DataIntentClassifier classifier = new DataIntentClassifier();

    @Test
    void should_classify_platform_stats() {
        assertThat(classifier.classify("咱们平台一共有多少家店")).isEqualTo(DataIntent.PLATFORM_STATS);
        assertThat(classifier.classify("平台共有多少个用户")).isEqualTo(DataIntent.PLATFORM_STATS);
    }

    @Test
    void should_classify_my_entity_and_clock() {
        assertThat(classifier.classify("查看我的博客")).isEqualTo(DataIntent.MY_ENTITY);
        assertThat(classifier.classify("今天几号")).isEqualTo(DataIntent.CLOCK);
    }

    @Test
    void should_classify_weather_shop_user_blog() {
        assertThat(classifier.classify("长沙天气怎么样")).isEqualTo(DataIntent.WEATHER);
        assertThat(classifier.classify("附近有什么餐厅")).isEqualTo(DataIntent.SHOP);
        assertThat(classifier.classify("这篇博客的作者是谁")).isEqualTo(DataIntent.USER);
    }

    @Test
    void should_return_none_for_chitchat_and_blank() {
        assertThat(classifier.classify("你好")).isEqualTo(DataIntent.NONE);
        assertThat(classifier.classify("介绍一下你自己")).isEqualTo(DataIntent.NONE);
        assertThat(classifier.classify("")).isEqualTo(DataIntent.NONE);
        assertThat(classifier.classify(null)).isEqualTo(DataIntent.NONE);
    }
}
