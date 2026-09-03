package com.hmdp.agent.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.observation.ChatModelObservationConvention;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.retry.support.RetryTemplate;

import io.micrometer.observation.ObservationRegistry;
import lombok.extern.slf4j.Slf4j;

/**
 * ChatModel Bean 装配声明：主模型（@Primary 接管 auto-config）+ 压缩模型 + 压缩 ChatClient。
 * <p>
 * 装配逻辑见 {@link ChatModelAssembler}（本类只声明）。压缩调用点（摘要器等）统一走
 * {@code compressChatClient} 并打 {@code SUBAGENT_COMPRESS} 观测标记。
 * </p>
 */
@Slf4j
@Configuration
public class ChatModelConfig {

    @Bean
    @Primary
    public OpenAiChatModel mainChatModel(@Qualifier("customOpenAiApi") OpenAiApi openAiApi,
                                         ObjectProvider<ToolCallingManager> toolCallingManager,
                                         ObjectProvider<RetryTemplate> retryTemplate,
                                         ObservationRegistry observationRegistry,
                                         ObjectProvider<ChatModelObservationConvention> observationConvention,
                                         @Value("${spring.ai.openai.chat.options.model}") String model) {
        OpenAiChatModel chatModel = ChatModelAssembler.mainModel(
                openAiApi, model, toolCallingManager, retryTemplate, observationRegistry, observationConvention);
        log.info("主 ChatModel 装配完成（接管 auto-config），model={}", model);
        return chatModel;
    }

    @Bean("compressChatModel")
    public OpenAiChatModel compressChatModel(CompressModelProperties properties,
                                             @Qualifier("customOpenAiApi") OpenAiApi mainOpenAiApi,
                                             ObjectProvider<ToolCallingManager> toolCallingManager,
                                             ObjectProvider<RetryTemplate> retryTemplate,
                                             ObservationRegistry observationRegistry,
                                             ObjectProvider<ChatModelObservationConvention> observationConvention) {
        OpenAiApi api = properties.customConfigured()
                ? OpenAiApi.builder().baseUrl(properties.getBaseUrl()).apiKey(properties.getApiKey()).build()
                : mainOpenAiApi;
        OpenAiChatModel chatModel = ChatModelAssembler.compressModel(
                api, properties, toolCallingManager, retryTemplate, observationRegistry, observationConvention);
        log.info("压缩 ChatModel 装配完成，provider={}，model={}",
                properties.getProvider(), properties.getModel());
        return chatModel;
    }

    @Bean("compressChatClient")
    public ChatClient compressChatClient(@Qualifier("compressChatModel") org.springframework.ai.chat.model.ChatModel model) {
        return ChatClient.builder(model).build();
    }
}