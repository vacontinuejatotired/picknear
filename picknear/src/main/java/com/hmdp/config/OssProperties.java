package com.hmdp.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * OSS 配置 — 从 application-{profile}.yaml 读取 app.oss 配置
 * <p>
 * 使用 @ConfigurationProperties 而非 @Value，IDE 可识别 app.oss.* 自动补全
 * prod: endpoint=${OSS_ENDPOINT}, bucket=hm-dianping-images
 * <p>
 * OssConfig 和 OssFileServiceImpl 通过此注入配置，保持一致的数据来源
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.oss")
public class OssProperties {

    /** OSS 地域，例如 oss-cn-beijing */
    private String region;

    /** OSS 地域节点，例如 oss-cn-hangzhou.aliyuncs.com */
    private String endpoint;

    /** OSS Bucket 名称 */
    private String bucket;

    /** 阿里云 AccessKey ID，通过环境变量注入 */
    private String accessKeyId;

    /** 阿里云 AccessKey Secret，通过环境变量注入 */
    private String accessKeySecret;
}
