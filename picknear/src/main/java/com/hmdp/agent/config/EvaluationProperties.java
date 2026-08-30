package com.hmdp.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 评测配置（Agent 任务完成质量评估，评测设计文档 §6.2）。
 * <p>
 * 配置项前缀：agent.evaluation
 * </p>
 * <p>
 * judge 模型支持两种来源，yaml 一行切换：
 * <ul>
 *   <li>{@code default}（默认）：Langfuse 托管 judge 模型，走平台免费额度，零配置</li>
 *   <li>{@code custom}：自定义 OpenAI-compatible 端点（如 DashScope MaaS compatible-mode），
 *       复用现有 API Key；base-url/api-key/model 三键齐备才算完整配置（{@link JudgeModel#isCustomConfigured()}）</li>
 * </ul>
 * </p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "agent.evaluation")
public class EvaluationProperties {

    /** 评测总开关（评估器/触发功能启用后置 true） */
    private boolean enabled = false;

    /** judge 模型配置（评估打分用 LLM） */
    private JudgeModel judgeModel = new JudgeModel();

    @Data
    public static class JudgeModel {

        /** 模型来源：default=Langfuse 托管 judge（免费额度）；custom=自定义 OpenAI-compatible 端点 */
        private String provider = "default";

        /** 自定义端点 base-url（provider=custom 时生效，如 DashScope MaaS compatible-mode） */
        private String baseUrl = "";

        /** 自定义 API Key（provider=custom 时生效） */
        private String apiKey = "";

        /** 自定义模型名（provider=custom 时生效，如 qwen-plus-2025-07-28） */
        private String model = "";

        /** 自定义连接是否完整配置：provider=custom 且 base-url/api-key/model 三键非空 */
        public boolean isCustomConfigured() {
            return "custom".equalsIgnoreCase(provider)
                    && !baseUrl.isBlank() && !apiKey.isBlank() && !model.isBlank();
        }
    }
}
