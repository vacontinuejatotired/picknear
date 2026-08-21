package com.hmdp.agent.dag.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 顺序执行注解（标记工具必须顺序执行，禁止并行）
 * 
 * <p>某些工具可能不适合并行执行（如需要严格顺序的文件操作、数据库事务等），
 * 使用此注解标记后，该工具会被单独分到一个层，确保顺序执行。</p>
 * 
 * <p>示例：</p>
 * <pre>
 * @Tool
 * {@literal @}SequentialOnly(reason = "文件操作需要严格顺序")
 * public FileResult writeToFile(String content) {
 *     // 此工具会被单独分层，不会与其他工具并行执行
 * }
 * </pre>
 *
 * @author DAG Planning Executor
 * @version 1.9
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface SequentialOnly {
    
    /**
     * 原因说明（可选，用于文档和日志）
     */
    String reason() default "";
}
