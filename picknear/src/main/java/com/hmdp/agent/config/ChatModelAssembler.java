package com.hmdp.agent.config;

import org.springframework.ai.chat.observation.ChatModelObservationConvention;
import org.springframework.ai.model.tool.DefaultToolCallingManager;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.retry.support.RetryTemplate;

import io.micrometer.observation.ObservationRegistry;

/**
 * ChatModel 装配逻辑（主模型 + 压缩模型）。单一职责：构造逻辑外置，config 只留 Bean 声明，可单测。
 * <p>
 * 主模型接管原因：新增任意 {@code ChatModel} bean 会让
 * {@code OpenAiChatAutoConfiguration.openAiChatModel}(@ConditionalOnMissingBean) 整体退避，
 * 因此必须在用户配置中<strong>同时定义主模型（@Primary）与压缩模型</strong>，显式还原主模型行为
 * （复用 {@code customOpenAiApi} 连接池 + {@code spring.ai.openai.chat.options.model} + 观测约定）。
 * </p>
 */
public final class ChatModelAssembler {

    private ChatModelAssembler() {
    }

    /** 主对话/规划共用的模型：还原 spring-ai 自动配置行为（api-key/base-url 走 customOpenAiApi 池）。 */
    public static OpenAiChatModel mainModel(OpenAiApi api, String model,
            ObjectProvider<ToolCallingManager> toolCallingManager,
            ObjectProvider<RetryTemplate> retryTemplate,
            ObservationRegistry observationRegistry,
            ObjectProvider<ChatModelObservationConvention> observationConvention) {

        OpenAiChatModel chatModel = OpenAiChatModel.builder()
                .openAiApi(api)
                .defaultOptions(OpenAiChatOptions.builder().model(model).build())
                .toolCallingManager(resolveToolCallingManager(toolCallingManager))
                .retryTemplate(resolveRetryTemplate(retryTemplate))
                .observationRegistry(observationRegistry)
                .build();
        observationConvention.ifAvailable(chatModel::setObservationConvention);
        return chatModel;
    }

    /** 压缩小模型：与主端点同源换 model（inherit），或走 custom 端点；观测约定与主模型一致（generation 名前缀区分）。 */
    public static OpenAiChatModel compressModel(OpenAiApi api, CompressModelProperties props,
            ObjectProvider<ToolCallingManager> toolCallingManager,
            ObjectProvider<RetryTemplate> retryTemplate,
            ObservationRegistry observationRegistry,
            ObjectProvider<ChatModelObservationConvention> observationConvention) {

        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(props.getModel())
                .temperature(props.getTemperature() != null ? props.getTemperature() : 0.2)
                .maxTokens(props.getMaxTokens() != null ? props.getMaxTokens() : 1200)
                .build();
        OpenAiChatModel chatModel = OpenAiChatModel.builder()
                .openAiApi(api)
                .defaultOptions(options)
                .toolCallingManager(resolveToolCallingManager(toolCallingManager))
                .retryTemplate(resolveRetryTemplate(retryTemplate))
                .observationRegistry(observationRegistry)
                .build();
        observationConvention.ifAvailable(chatModel::setObservationConvention);
        return chatModel;
    }

    private static ToolCallingManager resolveToolCallingManager(ObjectProvider<ToolCallingManager> provider) {
        ToolCallingManager manager = provider.getIfAvailable();
        return manager != null ? manager : DefaultToolCallingManager.builder().build();
    }

    private static RetryTemplate resolveRetryTemplate(ObjectProvider<RetryTemplate> provider) {
        RetryTemplate template = provider.getIfAvailable();
        return template != null ? template : RetryTemplate.builder().build();
    }
}