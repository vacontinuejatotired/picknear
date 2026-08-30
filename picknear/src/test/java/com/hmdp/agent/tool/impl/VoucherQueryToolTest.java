package com.hmdp.agent.tool.impl;

import com.hmdp.dto.Result;
import com.hmdp.voucher.entity.Voucher;
import com.hmdp.voucher.entity.VoucherOrder;
import com.hmdp.voucher.order.VoucherOrderService;
import com.hmdp.voucher.service.IVoucherService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * VoucherQueryTool — 优惠券/订单查询工具测试。
 */
@ExtendWith(MockitoExtension.class)
class VoucherQueryToolTest {

    @Mock private IVoucherService voucherService;
    @Mock private VoucherOrderService voucherOrderService;
    @Mock private ToolContext toolContext;

    private VoucherQueryTool tool;

    @BeforeEach
    void setUp() {
        tool = new VoucherQueryTool();
        ReflectionTestUtils.setField(tool, "voucherService", voucherService);
        ReflectionTestUtils.setField(tool, "voucherOrderService", voucherOrderService);
    }

    @Test
    void queryVouchersByShop_should_return_projected_vouchers() {
        Voucher v = new Voucher().setId(5L).setTitle("满100减20").setSubTitle("通用券")
                .setPayValue(80L).setActualValue(100L).setType(0).setStock(50);
        when(voucherService.queryVoucherOfShop(3L)).thenReturn(Result.ok(List.of(v)));

        List<VoucherQueryTool.VoucherBrief> result = tool.queryVouchersByShop(3L);

        assertThat(result).as("应返回优惠券投影").hasSize(1);
        assertThat(result.get(0).title()).isEqualTo("满100减20");
        assertThat(result.get(0).actualValue()).isEqualTo(100L);
        assertThat(result.get(0).stock()).isEqualTo(50);
    }

    @Test
    void queryVouchersByShop_should_return_empty_on_failure() {
        when(voucherService.queryVoucherOfShop(3L)).thenReturn(Result.fail("店铺不存在"));

        List<VoucherQueryTool.VoucherBrief> result = tool.queryVouchersByShop(3L);

        assertThat(result).as("失败应返回空列表").isEmpty();
    }

    @Test
    void queryMyVoucherOrders_should_use_login_user_and_map_status() {
        when(toolContext.getContext()).thenReturn(Map.of("userId", 1L));
        List<VoucherOrder> orders = List.of(
                new VoucherOrder().setId(11L).setVoucherId(5L).setStatus(2));
        when(voucherOrderService.listByUserId(1L, com.hmdp.utils.constants.SystemConstants.MAX_PAGE_SIZE))
                .thenReturn(orders);

        List<VoucherQueryTool.VoucherOrderBrief> result = tool.queryMyVoucherOrders(toolContext);

        assertThat(result).as("应返回当前用户订单").hasSize(1);
        assertThat(result.get(0).id()).isEqualTo(11L);
        assertThat(result.get(0).voucherId()).isEqualTo(5L);
        assertThat(result.get(0).statusLabel()).as("status=2 应映射为已支付").isEqualTo("已支付");
    }
}
