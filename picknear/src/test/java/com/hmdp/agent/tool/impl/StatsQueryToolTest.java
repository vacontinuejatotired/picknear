package com.hmdp.agent.tool.impl;

import com.hmdp.content.blog.BlogQueryService;
import com.hmdp.shop.service.IShopService;
import com.hmdp.user.service.IUserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * StatsQueryTool — 数据统计查询工具测试。
 * <p>
 * 覆盖三个统计方法和零值边界。P3-S4 后博客统计改 BlogQueryService.countAll。
 */
@ExtendWith(MockitoExtension.class)
class StatsQueryToolTest {

    @Mock
    private BlogQueryService blogQueryService;

    @Mock
    private IUserService userService;

    @Mock
    private IShopService shopService;

    private StatsQueryTool tool;

    @BeforeEach
    void setUp() {
        tool = new StatsQueryTool();
        ReflectionTestUtils.setField(tool, "blogQueryService", blogQueryService);
        ReflectionTestUtils.setField(tool, "userService", userService);
        ReflectionTestUtils.setField(tool, "shopService", shopService);
    }

    @Test
    void queryTotalBlogs_should_return_count() {
        when(blogQueryService.countAll()).thenReturn(100L);

        String result = tool.queryTotalBlogs();

        assertThat(result).as("应包含博客总数 100").contains("100");
    }

    @Test
    void queryTotalUsers_should_return_count() {
        when(userService.count()).thenReturn(50L);

        String result = tool.queryTotalUsers();

        assertThat(result).as("应包含用户总数 50").contains("50");
    }

    @Test
    void queryTotalShops_should_return_count() {
        when(shopService.count()).thenReturn(30L);

        String result = tool.queryTotalShops();

        assertThat(result).as("应包含店铺总数 30").contains("30");
    }

    @Test
    void should_handle_zero_counts() {
        when(blogQueryService.countAll()).thenReturn(0L);
        when(userService.count()).thenReturn(0L);
        when(shopService.count()).thenReturn(0L);

        assertThat(tool.queryTotalBlogs()).as("博客数为 0").contains("0");
        assertThat(tool.queryTotalUsers()).as("用户数为 0").contains("0");
        assertThat(tool.queryTotalShops()).as("店铺数为 0").contains("0");
    }
}
