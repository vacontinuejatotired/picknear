package com.hmdp.agent.dag.plan;

import com.hmdp.agent.dag.annotation.DependsOn;
import com.hmdp.agent.dag.annotation.FromTool;
import com.hmdp.agent.dag.annotation.SequentialOnly;
import com.hmdp.agent.model.ParameterInfo;
import com.hmdp.agent.model.ToolMetadata;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 依赖图分析器
 * 
 * <p>负责：</p>
 * <ol>
 *   <li>扫描所有工具类，提取元数据</li>
 *   <li>分析参数依赖关系</li>
 *   <li>构建依赖图</li>
 *   <li>验证依赖完整性</li>
 * </ol>
 *
 * @author DAG Planning Executor
 * @version 1.9
 */
@Slf4j
@Component
public class GraphAnalyzer {
    
    /** 工具名 → 元数据映射 */
    private final Map<String, ToolMetadata> toolMetadataMap = new ConcurrentHashMap<>();
    
    /** 应用上下文（用于按需获取 Bean，避免循环依赖） */
    private final ApplicationContext applicationContext;
    
    public GraphAnalyzer(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }
    
    @PostConstruct
    public void init() {
        // 延迟获取所有 Bean，避免循环依赖
        // 使用 ObjectProvider 按需获取
        Map<String, Object> allBeans = applicationContext.getBeansOfType(Object.class);
        List<Object> toolInstances = allBeans.values().stream()
            .filter(this::hasToolMethod)
            .toList();
        scanAndRegister(toolInstances);
    }
    
    /**
     * 检查 Bean 是否包含 @Tool 注解的方法
     */
    private boolean hasToolMethod(Object bean) {
        Class<?> clazz = bean.getClass();
        for (Method method : clazz.getMethods()) {
            if (method.getAnnotation(Tool.class) != null) {
                return true;
            }
        }
        return false;
    }
    
    /**
     * 扫描并注册工具元数据
     */
    public void scanAndRegister(List<Object> tools) {
        for (Object toolInstance : tools) {
            scanToolClass(toolInstance);
        }
        validateDependencies();
        log.info("GraphAnalyzer 初始化完成，注册工具 {} 个: {}", 
            toolMetadataMap.size(), toolMetadataMap.keySet());
    }
    
    /**
     * 扫描单个工具类
     */
    private void scanToolClass(Object toolInstance) {
        Class<?> clazz = toolInstance.getClass();
        for (Method method : clazz.getMethods()) {
            Tool toolAnnotation = method.getAnnotation(Tool.class);
            if (toolAnnotation == null) continue;
            
            // 优先使用 @Tool 注解的 name 属性，否则使用方法名
            String toolName = toolAnnotation.name().isEmpty() 
                ? method.getName() 
                : toolAnnotation.name();
            
            // 获取依赖
            DependsOn dependsOn = method.getAnnotation(DependsOn.class);
            List<String> dependencies = dependsOn != null 
                ? Arrays.asList(dependsOn.toolName()) 
                : List.of();
            
            // 分析参数
            List<ParameterInfo> parameters = analyzeParameters(method, dependencies);
            
            // 检查是否顺序执行
            SequentialOnly seqOnly = method.getAnnotation(SequentialOnly.class);
            
            ToolMetadata metadata = ToolMetadata.builder()
                .name(toolName)
                .method(method)
                .returnType(method.getReturnType())
                .dependencies(dependencies)
                .parameters(parameters)
                .sequentialOnly(seqOnly != null)
                .sequentialReason(seqOnly != null ? seqOnly.reason() : null)
                .build();
            
            toolMetadataMap.put(toolName, metadata);
            log.debug("注册工具: {} -> {}, 依赖: {}, 顺序执行: {}", 
                toolName, method.getReturnType().getSimpleName(), 
                dependencies, seqOnly != null);
        }
    }
    
    /**
     * 分析参数：自动推断哪些参数来自依赖工具
     */
    private List<ParameterInfo> analyzeParameters(Method method, List<String> dependencies) {
        List<ParameterInfo> params = new ArrayList<>();
        Parameter[] methodParams = method.getParameters();
        
        for (Parameter param : methodParams) {
            Class<?> type = param.getType();
            
            // 1. 检查 @FromTool 注解
            FromTool fromTool = param.getAnnotation(FromTool.class);
            if (fromTool != null) {
                ToolMetadata depMeta = toolMetadataMap.get(fromTool.value());
                params.add(ParameterInfo.builder()
                    .name(param.getName())
                    .type(type)
                    .fromDependency(true)
                    .dependencyToolName(fromTool.value())
                    .dependencyReturnType(depMeta != null ? depMeta.getReturnType() : null)
                    .explicitSource(true)
                    .build());
                continue;
            }
            
            // 2. 类型匹配
            String dependencyTool = findDependencyByReturnType(type, dependencies);
            if (dependencyTool != null) {
                ToolMetadata depMeta = toolMetadataMap.get(dependencyTool);
                params.add(ParameterInfo.builder()
                    .name(param.getName())
                    .type(type)
                    .fromDependency(true)
                    .dependencyToolName(dependencyTool)
                    .dependencyReturnType(depMeta != null ? depMeta.getReturnType() : null)
                    .explicitSource(false)
                    .build());
                continue;
            }
            
            // 3. 参数名匹配
            String paramDep = findDependencyByParamName(param.getName(), dependencies);
            if (paramDep != null) {
                ToolMetadata depMeta = toolMetadataMap.get(paramDep);
                params.add(ParameterInfo.builder()
                    .name(param.getName())
                    .type(type)
                    .fromDependency(true)
                    .dependencyToolName(paramDep)
                    .dependencyReturnType(depMeta != null ? depMeta.getReturnType() : null)
                    .explicitSource(false)
                    .build());
                continue;
            }
            
            // 4. Agent 参数（基本类型或其他）
            params.add(ParameterInfo.builder()
                .name(param.getName())
                .type(type)
                .fromDependency(false)
                .build());
        }
        
        return params;
    }
    
    /**
     * 根据返回类型查找依赖工具
     */
    private String findDependencyByReturnType(Class<?> type, List<String> dependencies) {
        for (String dep : dependencies) {
            ToolMetadata depMeta = toolMetadataMap.get(dep);
            if (depMeta != null && type.isAssignableFrom(depMeta.getReturnType())) {
                return dep;
            }
        }
        return null;
    }
    
    /**
     * 根据参数名查找依赖工具
     */
    private String findDependencyByParamName(String paramName, List<String> dependencies) {
        for (String dep : dependencies) {
            if (paramName.equals(dep)) {
                return dep;
            }
        }
        return null;
    }
    
    /**
     * 验证所有工具的依赖关系
     */
    private void validateDependencies() {
        for (ToolMetadata meta : toolMetadataMap.values()) {
            for (String dep : meta.getDependencies()) {
                if (!toolMetadataMap.containsKey(dep)) {
                    log.warn("工具 {} 依赖的工具 {} 尚未注册", meta.getName(), dep);
                }
            }
            
            // 检查返回类型是否唯一
            for (ToolMetadata existing : toolMetadataMap.values()) {
                if (existing.getReturnType().equals(meta.getReturnType()) 
                    && !existing.getName().equals(meta.getName())) {
                    log.warn("工具 {} 和 {} 返回相同类型 {}，可能导致依赖注入歧义", 
                        meta.getName(), existing.getName(), meta.getReturnType().getSimpleName());
                }
            }
        }
    }
    
    /**
     * 构建依赖图（返回构建结果，包含未知工具信息）
     */
    public GraphBuildResult buildGraph(Collection<String> toolNames) {
        Map<String, List<String>> graph = new HashMap<>();
        List<String> unknownTools = new ArrayList<>();
        
        for (String toolName : toolNames) {
            ToolMetadata meta = toolMetadataMap.get(toolName);
            if (meta != null) {
                graph.put(toolName, meta.getDependencies());
            } else {
                unknownTools.add(toolName);
                log.warn("工具 {} 未注册", toolName);
            }
        }
        
        return GraphBuildResult.builder()
            .graph(graph)
            .unknownTools(unknownTools)
            .build();
    }
    
    /**
     * 获取工具元数据
     */
    public ToolMetadata getMetadata(String toolName) {
        return toolMetadataMap.get(toolName);
    }
    
    /**
     * 获取所有工具元数据
     */
    public Map<String, ToolMetadata> getAllMetadata() {
        return Collections.unmodifiableMap(toolMetadataMap);
    }
}
