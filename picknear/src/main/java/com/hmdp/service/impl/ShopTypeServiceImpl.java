package com.hmdp.service.impl;

import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.redis.RedisConstants;
import io.netty.util.internal.StringUtil;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 商铺类型服务实现 — 获取分类列表（Redis缓存）
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Override
    public Result queryList() {
    //TODO 以后用zSet改一下
        String key= RedisConstants.CACHE_SHOPTYPE_KEY;
        List<String> value = stringRedisTemplate.opsForList().range(key,0,-1);
        //先查缓存看是否存在
        if(value!=null&& !value.isEmpty()){
            List<ShopType> list = new ArrayList<>();
            for (String s : value) {
                ShopType shopType = JSONUtil.toBean(s, ShopType.class);
                list.add(shopType);
            }
    return Result.ok(list);
        }//不存在则查数据然后插入缓存中
        List<ShopType>list=query().list();
        if (list==null|| list.isEmpty()){
            return Result.fail("商店种类加载错误");
        }
        for (ShopType shopType:list){
            stringRedisTemplate.opsForList().rightPush(RedisConstants.CACHE_SHOPTYPE_KEY,JSONUtil.toJsonStr(shopType));
        }
        return Result.ok(list);
    }
}
