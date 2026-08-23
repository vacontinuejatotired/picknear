package com.hmdp.agent.execution.loop;

import java.util.List;

/**
 * 工具结果存储接口
 *
 * <p>负责存储工具执行结果，提供按索引、类型、名称获取结果的方法。</p>
 */
public interface ToolResultStore {

    /**
     * 存储工具执行结果（带类型信息）
     *
     * @param toolName   工具名称
     * @param result     执行结果
     * @param returnType 返回类型
     */
    void store(String toolName, Object result, Class<?> returnType);

    /**
     * 按索引获取当前层的结果
     *
     * <p>搜索范围：当前层（currentEntries）</p>
     *
     * @param index 索引
     * @param <T>   返回类型
     * @return 结果
     */
    <T> T get(int index);

    /**
     * 根据返回类型获取当前层的结果
     *
     * <p>搜索范围：当前层（currentEntries），避免跨层结果泄露</p>
     *
     * @param type 返回类型
     * @param <T>  返回类型
     * @return 结果
     */
    <T> T getByType(Class<T> type);

    /**
     * 根据工具名和类型获取当前层的结果
     *
     * <p>当多个工具返回相同类型时，通过工具名精确匹配</p>
     *
     * @param toolName 工具名称
     * @param type     返回类型
     * @param <T>      返回类型
     * @return 结果
     */
    <T> T getByTypeAndTool(Class<T> type, String toolName);

    /**
     * 根据工具名获取结果（带类型校验）
     *
     * <p>搜索范围：全局（allResults），可跨层获取</p>
     *
     * @param toolName 工具名称
     * @param type     返回类型
     * @param <T>      返回类型
     * @return 结果
     */
    <T> T getByName(String toolName, Class<T> type);

    /**
     * 设置当前执行层的结果条目列表
     *
     * @param entries 结果条目列表
     */
    void setCurrentLayerEntries(List<ToolResultEntry> entries);

    /**
     * 清空当前层上下文（层切换时调用）
     */
    void clearCurrentLayer();

    /**
     * 清空所有结果（新一轮执行时调用）
     */
    void clearAll();

    /**
     * 判断某个工具是否执行成功
     *
     * @param toolName 工具名称
     * @return true 表示执行成功
     */
    boolean isSuccess(String toolName);
}
