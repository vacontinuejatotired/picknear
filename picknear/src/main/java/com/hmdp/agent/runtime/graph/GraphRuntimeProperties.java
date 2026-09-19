package com.hmdp.agent.runtime.graph;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Graph Runtime 循环预算。
 */
@Data
@Component
@ConfigurationProperties(prefix = "agent.access.graph")
public class GraphRuntimeProperties {

    /** 单次 turn 最多进入 plan 节点的次数 */
    private int maxPlanIterations = 3;

    /** 单个计划内最多工具轮数 */
    private int maxToolRounds = 4;

    /** 单次 turn 最多工具调用数 */
    private int maxTotalToolCalls = 10;

    /** 单次 turn 最多模型调用数 */
    private int maxModelCalls = 12;

    /** 单次 turn 总超时（秒） */
    private int totalTimeoutSeconds = 90;
}
