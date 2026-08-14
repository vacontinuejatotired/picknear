/**
 * 废弃/回退代码归档区。
 * <p>
 * 此包（及子包 {@code legacy.task} / {@code legacy.plan} / {@code legacy.routing}）
 * 存放被新实现替代、但暂保留的 legacy 组件：
 * <ul>
 *   <li>{@code legacy.task} — 旧串行任务执行器（TaskExecutor/TaskQueue，由
 *       {@code feature.subagent.enabled=false} 回退路径使用，待研究后移除）；</li>
 *   <li>{@code legacy.plan} — 旧规划策略（LegacyPlanRouter，由
 *       {@code feature.tool-routing.enabled=false} 激活）；</li>
 *   <li>{@code legacy.routing} — 旧目录构建门面（ToolRouter）与死抽象（CatalogBuilder）。</li>
 * </ul>
 * </p>
 * <p>
 * <strong>约定：新代码禁止依赖本包</strong>；在用代码（如 TaskPlanner 回退路径）对
 * 本包的 import 即为"此处依赖 legacy"的显式标记。
 * </p>
 */
package com.hmdp.agent.legacy;
