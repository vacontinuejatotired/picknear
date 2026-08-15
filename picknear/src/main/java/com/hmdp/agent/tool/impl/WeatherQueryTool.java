package com.hmdp.agent.tool.impl;

import com.hmdp.agent.annotation.TargetTool;
import com.hmdp.agent.annotation.ToolMeta;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import lombok.extern.slf4j.Slf4j;

@TargetTool(active = true)
@Slf4j
public class WeatherQueryTool {

    /**
     * 查询指定城市的当前天气情况（温度、晴雨等）。
     * 用户问「天气」「冷不冷」「热不热」「气温多少」「什么天气」时触发。
     * @param city 城市名称，如：北京、上海、广州
     */
    @Tool(description = """
            查询某城市的当前天气（温度、晴雨），和「天气怎么样」「冷不冷/热不热」一起使用。
            参数city传入城市名即可，如：北京、上海。
            """)
    @ToolMeta(keywords = {"天气", "气温", "温度", "冷不冷", "热不热", "下雨", "晴天", "冷吗", "热吗"}, intents = {"weather"})
    public String queryWeather(@ToolParam(description = "城市名称，如：北京、上海、广州") String city) {
        log.info("queryWeather: {}", city);
        return "The weather in " + city + " is sunny";
    }
}
