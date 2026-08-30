package com.hmdp.agent.tool.impl;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WeatherQueryTool — 天气查询工具测试。
 * <p>
 * 纯逻辑方法，无外部依赖，直接 new 调用。
 */
class WeatherQueryToolTest {

    private final WeatherQueryTool tool = new WeatherQueryTool();

    @Test
    void should_return_weather_string() {
        String result = tool.queryWeather("北京");

        assertThat(result).as("应包含城市名和天气信息")
                .contains("北京")
                .contains("sunny");
    }

    @Test
    void should_handle_null_city() {
        // 实现中 null + "字符串" → "null" 文本，不抛 NPE
        String result = tool.queryWeather(null);

        assertThat(result).as("null 城市不应抛异常").isNotNull();
    }
}
