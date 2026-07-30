package com.hmdp.controller;


import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.service.IShopService;
import com.hmdp.utils.constants.SystemConstants;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.util.List;

import org.springframework.web.bind.annotation.*;

import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;


/**
 * <p>
 * 商铺控制器 — 商铺CRUD、按类型/名称/距离查询
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@RestController
@RequestMapping("/shop")
@Tag(name = "商铺模块", description = "商铺信息查询、搜索、附近推荐等接口")
public class ShopController {

    @Resource
    public IShopService shopService;

    /**
     * 根据id查询商铺信息
     * @param id 商铺id
     * @return 商铺详情数据
     */
    @GetMapping("/{id}")
    @Operation(summary = "查询商铺详情", description = "根据商铺ID获取商铺详细信息")
    public Result queryShopById(
            @Parameter(description = "商铺ID") @PathVariable("id") Long id) {

        return shopService.queryById(id);
    }

    /**
     * 新增商铺信息
     * @param shop 商铺数据
     * @return 商铺id
     */
    @PostMapping
    @Operation(summary = "新增商铺", description = "创建新的商铺信息")
    public Result saveShop(
            @Parameter(description = "商铺信息") @RequestBody Shop shop) {
        // 写入数据库
        shopService.save(shop);
        // 返回店铺id
        return Result.ok(shop.getId());
    }

    /**
     * 更新商铺信息
     * @param shop 商铺数据
     * @return 无
     */
    @PutMapping
    @Operation(summary = "更新商铺", description = "更新商铺信息")
    public Result updateShop(
            @Parameter(description = "商铺信息") @RequestBody Shop shop) {
        // 写入数据库

        return shopService.updateShop(shop);
    }

    /**
     * 根据商铺类型分页查询商铺信息
     * @param typeId 商铺类型
     * @param current 页码
     * @return 商铺列表
     */
    @GetMapping("/of/type")
    @Operation(summary = "按类型查询商铺", description = "根据商铺类型分页查询，支持按距离排序")
    public Result queryShopByType(
            @Parameter(description = "商铺类型ID") @RequestParam("typeId") Integer typeId,
            @Parameter(description = "页码") @RequestParam(value = "current", defaultValue = "1") Integer current,
            @Parameter(description = "经度") @RequestParam(value = "x",required = false)Double x,
            @Parameter(description = "纬度") @RequestParam(value = "y",required = false)Double y,
            @Parameter(description = "排序方式") @RequestParam(value = "sortBy",required = false)String sortBy) {
        return shopService.queryShopByType(typeId,current,x,y,sortBy);
    }

    /**
     * 根据商铺名称关键字分页查询商铺信息
     * @param name 商铺名称关键字
     * @param current 页码
     * @return 商铺列表
     */
    @GetMapping("/of/name")
    @Operation(summary = "按名称搜索商铺", description = "根据商铺名称关键字搜索商铺")
    public Result queryShopByName(
            @Parameter(description = "商铺名称关键字") @RequestParam(value = "name", required = false) String name,
            @Parameter(description = "页码") @RequestParam(value = "current", defaultValue = "1") Integer current) {
        // 根据类型分页查询
        Page<Shop> page = shopService.query()
                .like(StrUtil.isNotBlank(name), "name", name)
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 返回数据
        return Result.ok(page.getRecords());
    }
    //查询5km内的附近店铺
    @GetMapping("/near/{typeId}")
    @Operation(summary = "查询附近商铺", description = "查询指定范围内的附近商铺")
    public Result queryNearShop(
            @Parameter(description = "商铺类型ID") @PathVariable Integer typeId,
            @Parameter(description = "经度") @RequestParam Double x,
            @Parameter(description = "纬度") @RequestParam Double y) {
        if (typeId == null) {
            return Result.fail("请传入商铺类型");
        }
        if(x==null||y==null){
            return Result.fail("请传入位置(x,y)坐标");
        }
        List<Shop> hotShopList = shopService.getHotShop(x,y,typeId);
        if(hotShopList.isEmpty()){
            return Result.fail("周边暂无店铺");
        }
        return Result.ok(hotShopList);
    }
    
}
