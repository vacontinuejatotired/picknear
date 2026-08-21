package com.hmdp.agent.model;

import lombok.Builder;
import lombok.Data;

import java.lang.reflect.Method;
import java.util.List;

/**
 * 工具元数据
 * 
 * <p>描述一个工具的完整信息，包括名称、方法、返回类型、依赖关系等。</p>
 * <p>由 {@link com.hmdp.agent.dag.plan.GraphAnalyzer} 在启动时扫描生成。</p>
 *
 * @author DAG Planning Executor
 * @version 1.9
 */
@Data
@Builder
public class ToolMetadata {
    
    /** 工具名称（@Tool 注解的 name 属性或方法名） */
    private String name;
    
    /** 工具方法 */
    private Method method;
    
    /** 返回类型 */
    private Class<?> returnType;
    
    /** 依赖的工具名称列表 */
    @Builder.Default
    private List<String> dependencies = List.of();
    
    /** 参数列表 */
    @Builder.Default
    private List<ParameterInfo> parameters = List.of();
    
    /** 是否标记为 SequentialOnly（禁止并行） */
    private boolean sequentialOnly;
    
    /** SequentialOnly 原因说明 */
    private String sequentialReason;
}
