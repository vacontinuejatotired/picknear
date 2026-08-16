/**
 * 回退链组件区（由 feature 开关控制的第二套"规划→执行"链，非死代码）。
 * <p>
 * 本包存放被新实现替代、但作为回退路径保留的 legacy 组件，与活链（Tree 链）
 * 并存：开关关闭时回退链仍是被选中的可用路径。
 * <ul>
 *   <li>{@code legacy.plan} — 回退规划链：{@code LegacyPlanRouter}（
 *       {@code feature.tool-routing.enabled=false} 条件装配）+ 内部路由门面
 *       {@code ToolRouter}（紧凑目录 + __UNCERTAIN__ 全量重跑）；</li>
 *   <li>{@code legacy.task} — 回退执行链：{@code TaskExecutor}/{@code TaskQueue}
 *       （{@code feature.subagent.enabled=false} 时由 FallbackRoundExecutor 实例化使用）。</li>
 * </ul>
 * </p>
 * <p>
 * 依赖方向：legacy 可以依赖活链组件（如 {@code CompactCatalogBuilder}），
 * 活链禁止反向依赖本包。
 * <strong>约定：新代码禁止 import 本包</strong>；回退适配器
 * （{@code FallbackRoundExecutor}）是唯一合法入口。活代码对本包的 import
 * 即为"此处依赖 legacy"的显式标记，需先解耦（如 TaskReportHelper.merge 已改为
 * 接收 List&lt;SubTask&gt;）。
 * </p>
 */
package com.hmdp.agent.legacy;
