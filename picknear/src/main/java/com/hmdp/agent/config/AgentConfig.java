package com.hmdp.agent.config;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.hmdp.agent.tool.ToolBeanCollector;

import ch.qos.logback.classic.Logger;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;

import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.memory.repository.jdbc.JdbcChatMemoryRepository;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
@Slf4j
public class AgentConfig {

    @Resource
    private JdbcTemplate jdbcTemplate;

    @PostConstruct
    void checkLogLevels() {
        log.info("========== 日志级别诊断 ==========");
        String[] checkLoggers = {
                "com.hmdp",
                "com.hmdp.agent",
                "com.hmdp.agent.tool",
                "com.hmdp.promptguard",
                "com.hmdp.promptguard.GuardedToolCallback",
                "com.hmdp.permission",
        };
        for (String name : checkLoggers) {
            Logger l = (Logger) LoggerFactory.getLogger(name);
            log.info("Logger[{}] level={} effective={} debugEnabled={}",
                    name, l.getLevel(), l.getEffectiveLevel(), l.isDebugEnabled());
        }
        log.info("========== 诊断结束 ==========");
    }

    /**
     * AI 专用线程池（用于流式响应中的异步 AI 调用）
     * <p>
     * 避免使用 ForkJoinPool.commonPool()，防止与项目其他异步任务竞争线程。
     * </p>
     */
    @Bean("aiTaskExecutor")
    public Executor aiTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("ai-worker-");
        executor.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }

    /**
     * 子任务执行线程池（用于 TaskPlanner 异步规划执行）
     * <p>
     * 核心 10 线程，队列 200，满队列由调用者线程执行。
     * </p>
     */
    @Bean("subtaskExecutor")
    public Executor subtaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(10);
        executor.setMaxPoolSize(50);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("subtask-");
        executor.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }

    /**
     * 对话记忆（用于多轮对话）
     */
    @Bean
    public ChatMemory chatMemory() {
        // 生产环境建议使用 RedisChatMemory
        JdbcChatMemoryRepository repository = JdbcChatMemoryRepository.builder().jdbcTemplate(jdbcTemplate).build();
        return MessageWindowChatMemory.builder().maxMessages(10).chatMemoryRepository(repository).build();
    }

    /**
     * ChatClient（AI 客户端）
     * <p>
     * 工具自动注入：{@link ToolBeanCollector} 在启动时扫描所有含 {@code @Tool} 方法的 Bean，
     * 无需手动 {@code @Resource} 每个工具类。
     */
    @Bean("aliibabaChatClient")
    public ChatClient chatClient(DashScopeChatModel chatModel, ChatMemory chatMemory,
                                 ToolBeanCollector toolBeanCollector) {
        ToolCallback[] toolCallbacks = toolBeanCollector.getToolCallbacks();

        ChatClient chatClient = ChatClient.builder(chatModel)
                        // 系统提示词已外置：由 AiServiceImpl/TaskPlanner 每次请求经 PromptService 注入
                        // 对话记忆
                        .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                        .build();
        log.info("ChatClient 构建完成，无默认工具（工具由 TaskPlanner 规划后调用）");

        return chatClient;
    }

    // @Bean
    // public ChatClient chatClient(OpenAiChatModel chatModel, ChatMeory chatMemory) {
    //     return ChatClient.builder(chatModel)
    //             .defaultSystem("你是一个专业的电商智能客服，名叫"小黑助手"。")
    //             .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
    //             .build();
    // }

    /**
     * 子 Agent ChatClient（不带默认工具）。
     * <p>
     * 与主 aliibabaChatClient 的区别：
     * <ul>
     *   <li>主 Client：Phase 1 纯文本回复，不绑工具</li>
     *   <li>子 Agent Client：Phase 2 工具执行，运行时通过 {@code .tools(filteredCallbacks)} 动态绑定</li>
     * </ul>
     * </p>
     * 注意：
     * <ul>
     *   <li>不在这里 .defaultTools() 绑定全部工具——由 SubTaskAgent 按 plan.tasks 动态筛选后传入</li>
     *   <li>不加对话记忆（ChatMemory）——子 Agent 每次调用独立，上下文由 TaskPlanner 的 currentResponse 传递</li>
     * </ul>
     */
    @Bean("subAgentChatClient")
    public ChatClient subAgentChatClient(DashScopeChatModel chatModel) {
        // 系统提示词已外置：由 SubTaskAgent 每次请求经 PromptService 注入（agent.system.subagent）
        return ChatClient.builder(chatModel)
                .build();
    }
}
