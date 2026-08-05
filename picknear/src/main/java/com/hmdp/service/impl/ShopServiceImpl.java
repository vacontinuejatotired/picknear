package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.cache.CacheClient;
import com.hmdp.utils.redis.RedisConstants;
import com.hmdp.utils.constants.SystemConstants;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 商铺服务实现 — 多级缓存（Caffeine+Redis+DB）、缓存穿透/击穿/雪崩处理、按距离查询
 */
@Service
@Slf4j
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private CacheClient cacheClient;

    @Override
    public Result queryById(Long id) {
        Shop shop = cacheClient.queryWithLogicExpire(RedisConstants.CACHE_SHOP_KEY, id, Shop.class, this::getById, 20L, TimeUnit.SECONDS);
        if (shop == null) {
            log.info("Mysql中不存在该店铺数据，id={}",id);
                return Result.fail("店铺不存在");
        }
        return Result.ok(shop);
    }

    @Override
    @Transactional
    public Result updateShop(Shop shop) {
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("店铺id不存在");
        }
        updateById(shop);
        stringRedisTemplate.delete(RedisConstants.CACHE_SHOP_KEY + id);
        return Result.ok();
    }

    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y, String sortBy) {
        if (x == null || y == null) {
            Page<Shop> page = query()
                    .eq("type_id", typeId)
                    .orderByDesc(StrUtil.isNotBlank(sortBy) && isSortable(sortBy), sortColumn(sortBy))
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            return Result.ok(page);
        }
        int start = (current - 1) * SystemConstants.DEFAULT_PAGE_SIZE;
        int end = current * SystemConstants.DEFAULT_PAGE_SIZE;
        String key = "shop:geo:" + typeId;
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo()
                .search(key, GeoReference.fromCoordinate(x, y),
                        new Distance(5000),
                        RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().limit(end));
        if (results == null) {
            return Result.ok(Collections.emptyList());
        }

        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> content = results.getContent();
        if (content.size() < start) {
            return Result.ok(Collections.emptyList());
        }
        List<Long> ids = new ArrayList<>(content.size());
        Map<Long, Distance> map = new HashMap<>(content.size());
        content.stream().skip(start)
                .forEach(item -> {
                    String shopId = item.getContent().getName();
                    Distance distance = item.getDistance();
                    ids.add(Long.parseLong(shopId));
                    map.put(Long.parseLong(shopId), distance);
                });
        String idStr = StrUtil.join(",", ids);
        List<Shop> shops = query().in("id", ids).last("order by field (id," + idStr + ")").list();
        shops.forEach(item -> {
            item.setDistance(map.get(item.getId()).getValue());
        });
        return Result.ok(shops);

    }

    @Override
    public List<Shop> getHotShop(Double x, Double y, Integer typeId) {
        if (x == null || y == null) {
            return Collections.emptyList();
        }
        String key = "shop:geo:" + typeId;
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.boundGeoOps(key).search(GeoReference.fromCoordinate(x, y),
                new Distance(5, RedisGeoCommands.DistanceUnit.KILOMETERS),
                RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().limit(10));
        if (results == null) {
            return Collections.emptyList();
        }
        List<Shop> hotShopList = new ArrayList<>();
        hotShopList = results.getContent().stream().map(item -> {
            return getById(Long.parseLong(item.getContent().getName()));
        }).collect(Collectors.toList());
        return hotShopList;
    }

    /** 可排序字段白名单（camelCase → 实际列名），防止 SQL 注入 */
    private static final Map<String, String> SORTABLE_FIELDS = Map.of(
            "score", "score",
            "sold", "sold",
            "comments", "comments",
            "avgPrice", "avg_price"
    );

    private static boolean isSortable(String field) {
        return field != null && SORTABLE_FIELDS.containsKey(field);
    }

    /** 返回实际数据库列名 */
    private static String sortColumn(String field) {
        if (field == null) return null;
        return SORTABLE_FIELDS.getOrDefault(field, field);
    }
}
