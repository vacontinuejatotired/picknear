package com.hmdp.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 压缩小模型配置。
 * <p>
 * 配置项前缀：agent.compress-model
 * </p>
 * <ul>
 *   <li>{@code provider=inherit}（默认）：复用主模型端点（{@code customOpenAiApi} 连接池）与主 api-key，仅换 model；
 *       DashScope MaaS 兼容端点同源，model 用无日期后缀更稳（如 {@code qwen-flash}/qwen-turbo）。</li>
 *   <li>{@code provider=custom}：自配 base-url / api-key 指向独立 OpenAI 兼容端点。</li>
 * </ul>
 */
@Data
@Component
@ConfigurationProperties(prefix = "agent.compress-model")
public class CompressModelProperties {

    /** 压缩模型来源：inherit=复用主端点；custom=自配端点 */
    private String provider = "inherit";

    /** 压缩模型名（qwen-flash 档，便宜/低延迟；压缩走旁路小模型） */
    private String model = "qwen-flash";

    /** custom 时：OpenAI 兼容端点 base-url（到 /compatible-mode 为止） */
    private String baseUrl;

    /** custom 时：API Key */
    private String apiKey;

    /** 压缩生成温度（摘要建议低熵） */
    private Double temperature = 0.2;

    /** 压缩生成最大输出 token */
    private Integer maxTokens = 1200;

    /** provider=custom 且 baseUrl/apiKey 均非空才算自配；否则回退 inherit */
    public boolean customConfigured() {
        return "custom".equalsIgnoreCase(provider)
                && StringUtils.hasText(baseUrl)
                && StringUtils.hasText(apiKey);
    }
}