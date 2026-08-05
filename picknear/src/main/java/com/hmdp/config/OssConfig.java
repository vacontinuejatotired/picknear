package com.hmdp.config;

import com.aliyun.oss.ClientBuilderConfiguration;
import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.common.auth.CredentialsProvider;
import com.aliyun.oss.common.auth.DefaultCredentialProvider;
import com.aliyun.oss.common.comm.SignVersion;
import jakarta.annotation.Resource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * OSS 客户端配置 — 仅 prod 环境加载
 * 配置值从 OssProperties 读取（@ConfigurationProperties 生成 IDE 元数据）
 */
@Configuration
@Profile("prod")
public class OssConfig {

    @Resource
    private OssProperties ossProperties;

    @Bean
    public OSS ossClient() {
        CredentialsProvider credentialsProvider = new DefaultCredentialProvider(
                ossProperties.getAccessKeyId(), ossProperties.getAccessKeySecret());

        ClientBuilderConfiguration config = new ClientBuilderConfiguration();
        config.setSignatureVersion(SignVersion.V4);

        return OSSClientBuilder.create()
                .region(ossProperties.getRegion())
                .endpoint(ossProperties.getEndpoint())
                .credentialsProvider(credentialsProvider)
                .clientConfiguration(config)
                .build();
    }
}
