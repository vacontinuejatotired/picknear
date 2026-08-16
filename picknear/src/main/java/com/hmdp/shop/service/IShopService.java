package com.hmdp.shop.service;

import com.hmdp.dto.Result;
import com.hmdp.shop.entity.Shop;

import java.util.List;

import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
/**
 * 商铺服务接口 — 商铺CRUD、按类型/距离/名称查询、多级缓存管理
 */
public interface IShopService extends IService<Shop> {

    Result queryById(Long id);

    Result updateShop(Shop shop);

    Result queryShopByType(Integer typeId, Integer current, Double x, Double y, String sortBy);

    List<Shop> getHotShop(Double x, Double y, Integer typeId);

    /** 按类型分页查店（评分降序，agent 工具用，M-4 对齐） */
    List<Shop> listByTypeOrderByScore(Long typeId, int page, int size);

    /** 按名称关键字分页搜索（H-1 下沉，Controller 不再拼 query） */
    Result queryShopByName(String name, Integer current);
}
