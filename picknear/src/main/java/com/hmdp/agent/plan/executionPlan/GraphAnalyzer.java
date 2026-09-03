package com.hmdp.agent.plan.executionPlan;

import com.hmdp.agent.annotation.ToolMeta;
import com.hmdp.agent.plan.executionPlan.annotation.DependsOn;
import com.hmdp.agent.plan.executionPlan.annotation.FromTool;
import com.hmdp.agent.plan.executionPlan.annotation.SequentialOnly;
import com.hmdp.agent.plan.executionPlan.model.ParameterInfo;
import com.hmdp.agent.plan.executionPlan.model.ToolMetadata;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
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
        Map<String, Object> allBeans = applicationContext.getBeansOfType(Object.class);
        List<Object> toolInstances = allBeans.values().stream()
            .filter(this::hasToolMethod)
            .toList();
        scanAndRegister(toolInstances);
    }

    private boolean hasToolMethod(Object bean) {
        Class<?> clazz = bean.getClass();
        for (Method method : clazz.getMethods()) {
            if (method.getAnnotation(Tool.class) != null) {
                return true;
            }
        }
        return false;
    }

    public void scanAndRegister(List<Object> tools) {
        for (Object toolInstance : tools) {
            scanToolClass(toolInstance);
        }
        validateDependencies();
        log.info("GraphAnalyzer 初始化完成，注册工具 {} 个: {}",
            toolMetadataMap.size(), toolMetadataMap.keySet());
    }

    private void scanToolClass(Object toolInstance) {
        Class<?> clazz = toolInstance.getClass();
        for (Method method : clazz.getMethods()) {
            Tool toolAnnotation = method.getAnnotation(Tool.class);
            if (toolAnnotation == null) continue;

            String toolName = toolAnnotation.name().isEmpty()
                ? method.getName()
                : toolAnnotation.name();

            DependsOn dependsOn = method.getAnnotation(DependsOn.class);
            List<String> dependencies = dependsOn != null
                ? Arrays.asList(dependsOn.toolName())
                : List.of();

            List<ParameterInfo> parameters = analyzeParameters(method, dependencies);

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

    private List<ParameterInfo> analyzeParameters(Method method, List<String> dependencies) {
        List<ParameterInfo> params = new ArrayList<>();
        Parameter[] methodParams = method.getParameters();

        for (Parameter param : methodParams) {
            Class<?> type = param.getType();

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

            List<String> matchingTools = findDependenciesByReturnType(type, dependencies);

            if (matchingTools.size() == 1) {
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
                log.warn("参数 {} 类型 {} 匹配多个工具 {}，请使用 @FromTool 指定来源",
                    param.getName(), type.getSimpleName(), matchingTools);
                params.add(ParameterInfo.builder()
                    .name(param.getName())
                    .type(type)
                    .fromDependency(true)
                    .dependencyToolName(null)
                    .dependencyReturnType(null)
                    .explicitSource(false)
                    .ambiguous(true)
                    .build());
            } else {
                params.add(ParameterInfo.builder()
                    .name(param.getName())
                    .type(type)
                    .fromDependency(false)
                    .build());
            }
        }

        return params;
    }

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

    private String findDependencyByReturnType(Class<?> type, List<String> dependencies) {
        for (String dep : dependencies) {
            ToolMetadata depMeta = toolMetadataMap.get(dep);
            if (depMeta != null && type.isAssignableFrom(depMeta.getReturnType())) {
                return dep;
            }
        }
        return null;
    }

    private String findDependencyByParamName(String paramName, List<String> dependencies) {
        for (String dep : dependencies) {
            if (paramName.equals(dep)) {
                return dep;
            }
        }
        return null;
    }

    private void validateDependencies() {
        for (ToolMetadata meta : toolMetadataMap.values()) {
            for (String dep : meta.getDependencies()) {
                if (!toolMetadataMap.containsKey(dep)) {
                    log.warn("工具 {} 依赖的工具 {} 尚未注册", meta.getName(), dep);
                }
            }

            for (ParameterInfo param : meta.getParameters()) {
                if (param.isAmbiguous()) {
                    log.warn("工具 {} 的参数 {} 有歧义（多个工具返回相同类型 {}），请使用 @FromTool 指定来源",
                        meta.getName(), param.getName(), param.getType().getSimpleName());
                }
            }
        }

        log.info("GraphAnalyzer 初始化完成，注册工具 {} 个", toolMetadataMap.size());
    }

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

    public ToolMetadata getMetadata(String toolName) {
        return toolMetadataMap.get(toolName);
    }

    public Map<String, ToolMetadata> getAllMetadata() {
        return Collections.unmodifiableMap(toolMetadataMap);
    }
}
