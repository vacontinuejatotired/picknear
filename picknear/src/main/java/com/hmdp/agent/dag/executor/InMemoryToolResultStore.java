package com.hmdp.agent.dag.executor;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存工具结果存储实现
 * 
 * <p>使用 ConcurrentHashMap 存储工具执行结果，支持 synchronized 锁保护当前层访问。</p>
 *
 * @author DAG Planning Executor
 * @version 1.9
 */
@Slf4j
@Component
public class InMemoryToolResultStore implements ToolResultStore {
    
    /** 所有工具执行结果 */
    private final Map<String, Object> allResults = new ConcurrentHashMap<>();
    
    /** 失败原因 */
    private final Map<String, String> failedReasons = new ConcurrentHashMap<>();
    
    /** 工具名 → 返回类型映射 */
    private final Map<String, Class<?>> toolReturnTypes = new ConcurrentHashMap<>();
    
    /** 当前层结果条目（synchronized 锁保护） */
    private final Object layerLock = new Object();
    private List<ToolResultEntry> currentEntries;
    
    @Override
    public void store(String toolName, Object result, Class<?> returnType) {
        allResults.put(toolName, result);
        if (returnType != null) {
            toolReturnTypes.put(toolName, returnType);
        }
        log.debug("存储工具结果: {} -> {} (类型: {})", toolName, 
            result != null ? result.getClass().getSimpleName() : "null",
            returnType != null ? returnType.getSimpleName() : "未知");
    }
    
    @Override
    @SuppressWarnings("unchecked")
    public <T> T get(int index) {
        synchronized (layerLock) {
            if (currentEntries == null || index >= currentEntries.size()) {
                throw new IndexOutOfBoundsException("当前层没有索引 " + index + " 的结果");
            }
            
            ToolResultEntry entry = currentEntries.get(index);
            if (entry.getResult() == null) {
                return null;  // 失败工具返回 null
            }
            
            return (T) entry.getType().cast(entry.getResult());
        }
    }
    
    @Override
    @SuppressWarnings("unchecked")
    public <T> T getByType(Class<T> type) {
        // 只搜索当前层，避免跨层结果泄露
        synchronized (layerLock) {
            if (currentEntries == null) return null;
            return currentEntries.stream()
                .filter(e -> e.getResult() != null && type.isInstance(e.getResult()))
                .map(e -> (T) type.cast(e.getResult()))
                .findFirst()
                .orElse(null);
        }
    }
    
    @Override
    @SuppressWarnings("unchecked")
    public <T> T getByTypeAndTool(Class<T> type, String toolName) {
        // 根据工具名和类型精确匹配
        synchronized (layerLock) {
            if (currentEntries == null) return null;
            return currentEntries.stream()
                .filter(e -> e.getToolName().equals(toolName) 
                    && e.getResult() != null 
                    && type.isInstance(e.getResult()))
                .map(e -> (T) type.cast(e.getResult()))
                .findFirst()
                .orElse(null);
        }
    }
    
    @Override
    @SuppressWarnings("unchecked")
    public <T> T getByName(String toolName, Class<T> type) {
        // 搜索全局结果（跨层）
        Object result = allResults.get(toolName);
        if (result == null) return null;
        
        // 类型校验
        Class<?> actualType = toolReturnTypes.get(toolName);
        if (actualType != null && !type.isAssignableFrom(actualType)) {
            log.warn("工具 {} 返回类型 {} 与请求类型 {} 不匹配", 
                toolName, actualType.getSimpleName(), type.getSimpleName());
            return null;
        }
        
        return type.cast(result);
    }
    
    @Override
    public void setCurrentLayerEntries(List<ToolResultEntry> entries) {
        synchronized (layerLock) {
            this.currentEntries = entries;
        }
    }
    
    @Override
    public void clearCurrentLayer() {
        synchronized (layerLock) {
            this.currentEntries = null;
        }
    }
    
    @Override
    public void clearAll() {
        allResults.clear();
        failedReasons.clear();
        toolReturnTypes.clear();
        clearCurrentLayer();
    }
    
    @Override
    public boolean isSuccess(String toolName) {
        // 工具执行成功但返回 null（如删除操作）也算成功
        return allResults.containsKey(toolName) && !failedReasons.containsKey(toolName);
    }
    
    /**
     * 记录失败原因
     */
    public void recordFailure(String toolName, String reason) {
        failedReasons.put(toolName, reason);
    }
    
    /**
     * 获取失败原因
     */
    public String getFailureReason(String toolName) {
        return failedReasons.get(toolName);
    }
}
