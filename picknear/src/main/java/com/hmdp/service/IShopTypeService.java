package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
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
 * 商铺类型服务接口 — 获取分类列表（带缓存）
 */
public interface IShopTypeService extends IService<ShopType> {

    Result queryList();
}
