package com.hmdp.agent.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * EvaluationProperties 配置类测试。
 * <p>
 * 覆盖默认值（default judge / 关闭）与 isCustomConfigured 判定（provider=custom
 * 且 base-url/api-key/model 三键齐备才算完整配置）。纯 POJO，直接 new，不启动 Spring 容器。
 * </p>
 */
class EvaluationPropertiesTest {

    @Test
    void should_use_default_values() {
        EvaluationProperties props = new EvaluationProperties();

        assertThat(props.isEnabled()).as("默认关闭评测").isFalse();
        EvaluationProperties.JudgeModel judge = props.getJudgeModel();
        assertThat(judge.getProvider()).as("默认使用 Langfuse 托管 judge").isEqualTo("default");
        assertThat(judge.getBaseUrl()).as("默认无自定义端点").isEmpty();
        assertThat(judge.getApiKey()).as("默认无自定义 key").isEmpty();
        assertThat(judge.getModel()).as("默认无自定义模型").isEmpty();
        assertThat(judge.isCustomConfigured()).as("default 模式不算自定义配置").isFalse();
    }

    @Test
    void custom_should_be_configured_when_all_keys_present() {
        EvaluationProperties props = new EvaluationProperties();
        EvaluationProperties.JudgeModel judge = props.getJudgeModel();
        judge.setProvider("custom");
        judge.setBaseUrl("https://xxx.maas.aliyuncs.com/compatible-mode");
        judge.setApiKey("sk-test");
        judge.setModel("qwen-plus-2025-07-28");

        assertThat(judge.isCustomConfigured()).as("三键齐备应视为完整配置").isTrue();
    }

    @Test
    void custom_should_not_be_configured_when_any_key_missing() {
        EvaluationProperties.JudgeModel judge = new EvaluationProperties.JudgeModel();
        judge.setProvider("custom");
        judge.setBaseUrl("https://xxx.maas.aliyuncs.com/compatible-mode");
        judge.setModel("qwen-plus-2025-07-28");
        // api-key 缺失

        assertThat(judge.isCustomConfigured()).as("缺 api-key 不算完整配置").isFalse();
    }

    @Test
    void provider_should_be_case_insensitive() {
        EvaluationProperties.JudgeModel judge = new EvaluationProperties.JudgeModel();
        judge.setProvider("Custom");
        judge.setBaseUrl("u");
        judge.setApiKey("k");
        judge.setModel("m");

        assertThat(judge.isCustomConfigured()).as("provider 大小写不敏感").isTrue();
    }
}
