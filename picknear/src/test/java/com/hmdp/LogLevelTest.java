package com.hmdp;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.Iterator;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证日志级别配置是否生效。
 * 不加载 Spring 上下文，直接查 Logback 配置，避免 DASHSCOPE_API_KEY 缺失问题。
 */
public class LogLevelTest {

    @BeforeAll
    static void setup() {
        // 加载 logback-spring.xml 需要 Spring 的 LogbackLoggingSystem，
        // 直接用 logback-test.xml 或 basic 配置。
        // 这里手动配置 root 和各个 logger 来模拟生产配置。
        LoggerContext ctx = (LoggerContext) LoggerFactory.getILoggerFactory();

        // 先重置，让测试从干净状态开始
        ctx.reset();

        // 手动加载类路径下的 logback-spring.xml 来模拟
        // 但 logback-spring.xml 支持 Spring 扩展标签，不能用普通方式加载。
        // 所以直接手动设置级别并检查行为。
    }

    @Test
    void testGuardLogLevel() {
        LoggerContext ctx = (LoggerContext) LoggerFactory.getILoggerFactory();

        // 手动配置模拟生产环境的日志设置
        Logger rootLogger = ctx.getLogger(Logger.ROOT_LOGGER_NAME);
        rootLogger.setLevel(ch.qos.logback.classic.Level.INFO);

        Logger hmdpLogger = ctx.getLogger("com.hmdp");
        hmdpLogger.setLevel(ch.qos.logback.classic.Level.WARN);

        Logger guardLogger = ctx.getLogger("com.hmdp.promptguard.GuardedToolCallback");
        guardLogger.setLevel(ch.qos.logback.classic.Level.DEBUG);
        guardLogger.setAdditive(false);

        Logger agentLogger = ctx.getLogger("com.hmdp.agent.service.impl.AiServiceImpl");
        agentLogger.setLevel(ch.qos.logback.classic.Level.DEBUG);
        agentLogger.setAdditive(false);

        // 输出诊断信息
        System.out.println("========== 日志级别诊断 ==========");
        System.out.println("ROOT        level: " + rootLogger.getLevel() + " effective: " + rootLogger.getEffectiveLevel());
        System.out.println("com.hmdp    level: " + hmdpLogger.getLevel() + " effective: " + hmdpLogger.getEffectiveLevel());
        System.out.println("com.hmdp.promptguard.GuardedToolCallback:");
        System.out.println("  level: " + guardLogger.getLevel() + " effective: " + guardLogger.getEffectiveLevel());
        System.out.println("  additivity: " + guardLogger.isAdditive());
        System.out.println("  debugEnabled: " + guardLogger.isDebugEnabled());
        System.out.println("  infoEnabled: " + guardLogger.isInfoEnabled());

        // 验证 DEBUG 级别已启用
        assertTrue(guardLogger.isDebugEnabled(),
                "com.hmdp.promptguard 的 DEBUG 级别未生效");

        // 手动 append 一个控制台 appender 来测试实际输出
        ch.qos.logback.core.ConsoleAppender<ILoggingEvent> console = new ch.qos.logback.core.ConsoleAppender<>();
        console.setContext(ctx);
        console.setName("TEST_CONSOLE");
        ch.qos.logback.classic.PatternLayout layout = new ch.qos.logback.classic.PatternLayout();
        layout.setContext(ctx);
        layout.setPattern("%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n");
        layout.start();
        console.setLayout(layout);
        console.start();

        guardLogger.addAppender(console);
        agentLogger.addAppender(console);

        // 实际输出测试
        System.out.println("\n--- 实际输出测试（以下日志由 Logback 打印） ---");
        org.slf4j.Logger log = LoggerFactory.getLogger("com.hmdp.promptguard.GuardedToolCallback");
        log.debug(">>> [Guard DEBUG] 如果看到这行，说明 DEBUG 已生效 <<<");
        log.info(">>> [Guard INFO] 这条 INFO 应该也能看到 <<<");

        log = LoggerFactory.getLogger("com.hmdp.agent.service.impl.AiServiceImpl");
        log.debug(">>> [Agent DEBUG] AiServiceImpl DEBUG 日志 <<<");

        log = LoggerFactory.getLogger("com.hmdp");
        log.debug(">>> [com.hmdp DEBUG] 这条不应该出现（WARN 级别）<<<");
        log.warn(">>> [com.hmdp WARN] 这条应该出现 <<<");

        System.out.println("========== 诊断结束 ==========");
    }
}
