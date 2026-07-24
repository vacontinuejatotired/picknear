package com.hmdp.agent.tool.impl;

import com.hmdp.agent.annotation.TargetTool;
import com.hmdp.service.IBlogService;
import com.hmdp.service.IShopService;
import com.hmdp.service.IUserService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * 数据统计查询工具（用于测试任务分解）。
 * <p>
 * 提供博客数/用户数/店铺数查询。用户说"统计/查一下多少"等时触发。
 * 这些工具返回简单数字，适合验证 TaskPlanner 的 decompose → execute → merge 流程。
 * </p>
 */
@TargetTool(active = true)
@Slf4j
public class StatsQueryTool {

    @Resource
    private IBlogService blogService;

    @Resource
    private IUserService userService;

    @Resource
    private IShopService shopService;

    /**
     * 统计当前系统中的博客总数、
     * 「统计/总共多少博客/博客有多少/查一下博客数量」时使用。
     * 注意：是全部博客总数，不区分用户。
     */
    @Tool(description = """
            统计/查看当前系统中的博客总数，
            「统计博客」「共有多少博客」「博客有多少篇」「查一下博客数量」时使用。
            返回的是全部博客的总数，不区分用户。
            """)
    public String queryTotalBlogs() {
        long count = blogService.count();
        log.info("StatsQueryTool.queryTotalBlogs = {}", count);
        return "📝 当前共有 " + count + " 篇博客。";
    }

    /**
     * 统计当前系统中的注册用户总数、
     * 「统计用户/总共多少用户/查一下用户数」时使用。
     */
    @Tool(description = """
            统计/查看当前系统中的注册用户总数，
            「统计用户」「共有多少用户」「查一下用户数」「多少人注册」时使用。
            """)
    public String queryTotalUsers() {
        long count = userService.count();
        log.info("StatsQueryTool.queryTotalUsers = {}", count);
        return "👤 当前共有 " + count + " 位注册用户。";
    }

    /**
     * 统计当前系统中的店铺总数、
     * 「统计店铺/总共多少店铺/查一下店铺数量」时使用。
     */
    @Tool(description = """
            统计/查看当前系统中的店铺总数，
            「统计店铺」「共有多少店铺」「查一下店铺数量」「商铺有多少」时使用。
            """)
    public String queryTotalShops() {
        long count = shopService.count();
        log.info("StatsQueryTool.queryTotalShops = {}", count);
        return "🏪 当前共有 " + count + " 家店铺。";
    }
}
