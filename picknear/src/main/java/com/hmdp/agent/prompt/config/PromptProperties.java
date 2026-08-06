package com.hmdp.agent.prompt.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;

/**
 * 提示词外置配置。
 * <p>
 * 配置项前缀：agent.prompt。base-url/basic-auth 空时 {@link #isConfigured()} 为 false，
 * 全部走内置模板（本地 IDE 无环境变量时不发任何远程请求，Fail-Open）。
 * </p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "agent.prompt")
public class PromptProperties {

    /** 总开关：false 时全部走内置模板 */
    private boolean enabled = true;

    /** Langfuse 基础地址，绑定 ${LANGFUSE_BASE_URL:}，空则不可用 */
    private String baseUrl = "";

    /** Langfuse Basic 认证（裸 base64，不含 "Basic " 前缀），绑定 ${LANGFUSE_BASIC_AUTH:} */
    private String basicAuth = "";

    /** 默认拉取 label（生产环境建议 production） */
    private String defaultLabel = "production";

    /** 成功文本 / 404 负结果缓存 TTL */
    private Duration cacheTtl = Duration.ofMinutes(5);

    /** 网络失败瞬时熔断 TTL（防 Langfuse 宕机风暴） */
    private Duration failureCacheTtl = Duration.ofSeconds(30);

    /** RestClient 建连/读超时 */
    private Duration timeout = Duration.ofSeconds(2);

    /** 工具描述外置独立开关（false 时工具描述回退 @Tool 注解） */
    private boolean toolDescriptionEnabled = true;

    /** 种子端点开关（默认关，生产编排一次后关闭） */
    private boolean seedEnabled = false;

    public boolean isConfigured() {
        return StringUtils.hasText(baseUrl) && StringUtils.hasText(basicAuth);
    }
}
