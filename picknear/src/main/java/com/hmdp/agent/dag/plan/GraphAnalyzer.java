package com.hmdp.agent.dag.plan;

import com.hmdp.agent.annotation.ToolMeta;
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

            // 解析 @ToolMeta 注解（幂等性、重试配置）
            ToolMeta toolMeta = method.getAnnotation(ToolMeta.class);
            boolean idempotent = toolMeta != null ? toolMeta.idempotent() : true;
            int maxRetries = toolMeta != null ? toolMeta.maxRetries() : -1;
            int retryOnTimeout = toolMeta != null ? toolMeta.retryOnTimeout() : -1;

            ToolMetadata metadata = ToolMetadata.builder()
                .name(toolName)
                .method(method)
                .returnType(method.getReturnType())
                .dependencies(dependencies)
                .parameters(parameters)
                .sequentialOnly(seqOnly != null)
                .sequentialReason(seqOnly != null ? seqOnly.reason() : null)
                .idempotent(idempotent)
                .maxRetries(maxRetries)
                .retryOnTimeout(retryOnTimeout)
                .build();

            toolMetadataMap.put(toolName, metadata);
            log.debug("注册工具: {} -> {}, 依赖: {}, 顺序执行: {}, 幂等: {}, 最大重试: {}",
                toolName, method.getReturnType().getSimpleName(),
                dependencies, seqOnly != null, idempotent, maxRetries);
        }
    }
    
    /**
     * 分析参数：推断哪些参数来自依赖工具
     * 
     * <p>优先级：</p>
     * <ol>
     *   <li>@FromTool 注解：显式指定来源（最可靠）</li>
     *   <li>参数名匹配：参数名与工具名一致</li>
     *   <li>类型匹配（唯一）：只有唯一工具返回该类型</li>
     *   <li>类型匹配（有歧义）：多个工具返回相同类型，要求 @FromTool</li>
     * </ol>
     */
    private List<ParameterInfo> analyzeParameters(Method method, List<String> dependencies) {
        List<ParameterInfo> params = new ArrayList<>();
        Parameter[] methodParams = method.getParameters();
        
        for (Parameter param : methodParams) {
            Class<?> type = param.getType();
            
            // 1. 检查 @FromTool 注解（显式指定来源，最高优先级）
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
            
            // 2. 参数名匹配：参数名与依赖工具名一致
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
            
            // 3. 类型匹配：查找返回该类型的依赖工具
            List<String> matchingTools = findDependenciesByReturnType(type, dependencies);
            
            if (matchingTools.size() == 1) {
                // 唯一匹配，自动推断
                String toolName = matchingTools.get(0);
                ToolMetadata depMeta = toolMetadataMap.get(toolName);
                params.add(ParameterInfo.builder()
                    .name(param.getName())
                    .type(type)
                    .fromDependency(true)
                    .dependencyToolName(toolName)
                    .dependencyReturnType(depMeta != null ? depMeta.getReturnType() : null)
                    .explicitSource(false)
                    .build());
            } else if (matchingTools.size() > 1) {
                // 多个工具返回相同类型，要求使用 @FromTool
                log.warn("参数 {} 类型 {} 匹配多个工具 {}，请使用 @FromTool 指定来源",
                    param.getName(), type.getSimpleName(), matchingTools);
                // 标记为需要用户指定
                params.add(ParameterInfo.builder()
                    .name(param.getName())
                    .type(type)
                    .fromDependency(true)
                    .dependencyToolName(null)  // 需要用户指定
                    .dependencyReturnType(null)
                    .explicitSource(false)
                    .ambiguous(true)  // 标记为有歧义
                    .build());
            } else {
                // 无匹配，作为 Agent 参数
                params.add(ParameterInfo.builder()
                    .name(param.getName())
                    .type(type)
                    .fromDependency(false)
                    .build());
            }
        }
        
        return params;
    }
    
    /**
     * 根据返回类型查找所有匹配的依赖工具
     */
    private List<String> findDependenciesByReturnType(Class<?> type, List<String> dependencies) {
        List<String> result = new ArrayList<>();
        for (String dep : dependencies) {
            ToolMetadata depMeta = toolMetadataMap.get(dep);
            if (depMeta != null && type.isAssignableFrom(depMeta.getReturnType())) {
                result.add(dep);
            }
        }
        return result;
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
            // 检查依赖的工具是否已注册
            for (String dep : meta.getDependencies()) {
                if (!toolMetadataMap.containsKey(dep)) {
                    log.warn("工具 {} 依赖的工具 {} 尚未注册", meta.getName(), dep);
                }
            }
            
            // 检查参数是否有歧义
            for (ParameterInfo param : meta.getParameters()) {
                if (param.isAmbiguous()) {
                    log.warn("工具 {} 的参数 {} 有歧义（多个工具返回相同类型 {}），请使用 @FromTool 指定来源",
                        meta.getName(), param.getName(), param.getType().getSimpleName());
                }
            }
        }
        
        log.info("GraphAnalyzer 初始化完成，注册工具 {} 个", toolMetadataMap.size());
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
