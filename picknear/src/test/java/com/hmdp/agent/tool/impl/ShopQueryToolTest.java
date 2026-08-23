package com.hmdp.agent.tool.impl;

import com.baomidou.mybatisplus.extension.conditions.query.QueryChainWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.shop.entity.Shop;
import com.hmdp.shop.entity.ShopType;
import com.hmdp.shop.service.IShopService;
import com.hmdp.shop.service.IShopTypeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * ShopQueryTool — 店铺查询工具测试。
 */
@ExtendWith(MockitoExtension.class)
class ShopQueryToolTest {

    @Mock private IShopService shopService;
    @Mock private IShopTypeService shopTypeService;

    private ShopQueryTool tool;

    @BeforeEach
    void setUp() {
        tool = new ShopQueryTool();
        ReflectionTestUtils.setField(tool, "shopService", shopService);
        ReflectionTestUtils.setField(tool, "shopTypeService", shopTypeService);
    }

    @SuppressWarnings("unchecked")
    private QueryChainWrapper<Shop> mockQueryChain() {
        QueryChainWrapper<Shop> wrapper = mock(QueryChainWrapper.class);
        when(shopService.query()).thenReturn(wrapper);
        return wrapper;
    }

    @Test
    void queryShopTypes_should_return_projected_types() {
        when(shopTypeService.queryList())
                .thenReturn(Result.ok(List.of(new ShopType().setId(1L).setName("美食"),
                        new ShopType().setId(2L).setName("酒店"))));

        List<ShopQueryTool.ShopTypeBrief> result = tool.queryShopTypes();

        assertThat(result).as("应返回类型投影").hasSize(2);
        assertThat(result.get(0).name()).isEqualTo("美食");
        assertThat(result.get(1).id()).isEqualTo(2L);
    }

    @Test
    void queryShopsByType_should_page_by_max_size() {
        Shop shop = new Shop().setId(10L).setName("测试店").setAvgPrice(80L).setScore(45).setSold(100);
        when(shopService.listByTypeOrderByScore(1L, 1, 10)).thenReturn(List.of(shop));

        List<ShopQueryTool.ShopBrief> result = tool.queryShopsByType(1L, null);

        assertThat(result).as("应返回店铺投影").hasSize(1);
        assertThat(result.get(0).name()).isEqualTo("测试店");
        assertThat(result.get(0).avgPrice()).isEqualTo(80L);
        verify(shopService).listByTypeOrderByScore(1L, 1, 10);
    }

    @Test
    void queryShopById_should_return_detail() {
        Shop shop = new Shop().setId(3L).setName("海底捞").setArea("陆家嘴").setAddress("xx路1号")
                .setAvgPrice(120L).setScore(48).setSold(999).setOpenHours("10:00-22:00");
        when(shopService.queryById(3L)).thenReturn(Result.ok(shop));

        ShopQueryTool.ShopDetailBrief result = tool.queryShopById(3L);

        assertThat(result).as("应返回店铺详情").isNotNull();
        assertThat(result.name()).isEqualTo("海底捞");
        assertThat(result.area()).isEqualTo("陆家嘴");
        assertThat(result.openHours()).isEqualTo("10:00-22:00");
    }

    @Test
    void queryShopById_should_return_null_when_not_found() {
        when(shopService.queryById(999L)).thenReturn(Result.fail("店铺不存在"));

        ShopQueryTool.ShopDetailBrief result = tool.queryShopById(999L);

        assertThat(result).as("查不到应返回 null").isNull();
    }
}
