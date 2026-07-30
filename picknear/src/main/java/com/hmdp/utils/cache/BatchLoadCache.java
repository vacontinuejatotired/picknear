package com.hmdp.utils.cache;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.hmdp.entity.User;
import com.hmdp.entity.UserInfo;
import com.hmdp.entity.UserinfoCache;
import com.hmdp.service.IUserInfoService;
import com.hmdp.service.IUserService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 批量缓存加载工具 — 定时异步加载用户信息到 Redis，支持空值缓存防穿透
 */
@Component
@Slf4j
public class BatchLoadCache {
    @Resource
    private IUserService userService;
    @Resource
    private IUserInfoService userInfoService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private LoadingCache<String, UserinfoCache> userinfoCaffeine;
    /**
     * 用于处理任务的set
     */
    private volatile  Set<Long> usingUserIds = ConcurrentHashMap.newKeySet();

    /**
     *
     用于接受任务的set
     */
    private final   Set<Long> writingUserIds = ConcurrentHashMap.newKeySet();

    /**
     * 接受userId，然后先批量查redis，筛掉有的
     * redis不存在的再查mysql
     * mysql没有的返回空对象，在redis和本地缓存都存一个空对象
     *
     */
    @Scheduled(fixedRate = 2000)
    public void loadCache() {
        //操作时不允许新增，需要前后读取到的一致
        //转移期间不允许新加入id

        Set<Long> writing =new HashSet<>(writingUserIds);
        writingUserIds.clear();
        usingUserIds =writing;
        try {
            batchLoadCache(usingUserIds.stream().toList());
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            usingUserIds.clear();
        }
        //加载缓存逻辑
    }
    public void saveFuture(Long userId) {
        if(usingUserIds.contains(userId)) {

            log.info("用户{}的缓存正在更新中，已存在任务中，无需重复添加", userId);
            return;
        }
        writingUserIds.add(userId);
    }
    private void batchLoadCache(List<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return;
        }
        List<User> users = new ArrayList<>(userIds.size());
        List<UserinfoCache> userinfoCaches = new ArrayList<>(userIds.size());
        List<String> keys = new ArrayList<>(userIds.size());
        List<Long> needLoadFromMysqlToCache = new ArrayList<>(userIds.size());
        for (Long userId : userIds) {
            keys.add(CaffeineConstants.USERINFO_CACHE_KEY + userId);
        }
        try {
            //批量读redis
            List<Object> redisCache = stringRedisTemplate.executePipelined((RedisCallback<Object>) connection -> {
                for (String key : keys) {
                    connection.hGetAll(key.getBytes());
                }
                return null;
            });
            for (int i = 0; i < userIds.size(); i++) {
                Long userId = userIds.get(i);

                String redisKey = CaffeineConstants.USERINFO_CACHE_KEY + userId;
                Map<String,String> hashMap = (Map<String ,String>) redisCache.get(i);

                if (hashMap != null && !hashMap.isEmpty()) {
                    // Redis命中，转换为UserinfoCache
                    UserinfoCache cache = convertMapToUserinfoCache(userId, hashMap);
                    userinfoCaffeine.put(redisKey, cache);
                } else {
                    // Redis未命中，需要查数据库
                    needLoadFromMysqlToCache.add(userId);
                }
            }
        }
        catch (Exception e) {
        log.info("缓存更新失败{}",e.getMessage());
        }
        if(!needLoadFromMysqlToCache.isEmpty()) {
            batchLoadMysql(needLoadFromMysqlToCache);
        }
        log.info("缓存更新完成");
    }

    private UserinfoCache convertMapToUserinfoCache(Long userId, Map<String,String > hashMap) {
        String nickName = "";
        String icon = "";
        for (Map.Entry<String,String> entry : hashMap.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            switch (key) {
                case "nickName":
                    nickName = value;
                    break;
                case "icon":
                    icon = value;

                    break;
            }
        }
        return new UserinfoCache(userId, nickName, icon);
    }


    private void batchLoadMysql(List<Long> userIds) {
        //空值用户信息
        Set<Long> invalidValueUserIds = new HashSet<>(userIds.size());
        //有效用户信息
        Set<Long> validValueUserIds = new HashSet<>(userIds.size());
        Map<String,Map> validUsersInRedis = new HashMap<>(userIds.size());
        Map<String,Map> invalidUsersInRedis = new HashMap<>(userIds.size());
        List<User> userList = userService.query().in("id", userIds).select("id").list();
        // nickName、icon 已迁移到 tb_user_info，批量查询
        List<UserInfo> userInfoList = userInfoService.listByIds(userIds);
        Map<Long, String> nickNameMap = new HashMap<>(userInfoList.size());
        Map<Long, String> iconMap = new HashMap<>(userInfoList.size());
        for (UserInfo ui : userInfoList) {
            nickNameMap.put(ui.getUserId(), ui.getNickName());
            iconMap.put(ui.getUserId(), ui.getIcon());
        }
        UserinfoCache userinfoCache  =new UserinfoCache();
        for (User user : userList) {
            //mysql没查到的用户则让redis存空值，本地也存空值
            String key = CaffeineConstants.USERINFO_CACHE_KEY + user.getId();
            if(!usingUserIds.contains(user.getId())&&!invalidValueUserIds.contains(user.getId())) {
                userinfoCache.setId(user.getId());
                userinfoCache.setNickName("");
                userinfoCache.setIcon("");
                //塞入set等下一起缓存
                invalidValueUserIds.add(user.getId());
                invalidUsersInRedis.put(key,convertUserinfoCacheToMap(userinfoCache));
            }
            else {
                userinfoCache.setId(user.getId());
                userinfoCache.setNickName(nickNameMap.getOrDefault(user.getId(), ""));
                userinfoCache.setIcon(iconMap.getOrDefault(user.getId(), ""));
                validValueUserIds.add(user.getId());
                validUsersInRedis.put(key,convertUserinfoCacheToMap(userinfoCache));
            }
            userinfoCaffeine.put(key, userinfoCache);
        }
        //史
        HashMap<String,Map<String,Map>> userinfoCacheMap = new HashMap<>(4);
        userinfoCacheMap.put("validUsersInRedis",validUsersInRedis);
        userinfoCacheMap.put("invalidUsersInRedis",invalidUsersInRedis);
        batchLoadRedis(userinfoCacheMap);
    }

    /**
     * 批量写入Redis（区分有效用户和空值用户）
     * @param userinfoCacheMap 包含有效用户和空值用户的Map
     */
    private void batchLoadRedis(Map<String, Map<String, Map>> userinfoCacheMap) {
        Map<String, Map> validUsersInRedis = userinfoCacheMap.get("validUsersInRedis");
        Map<String, Map> invalidUsersInRedis = userinfoCacheMap.get("invalidUsersInRedis");
        try {
            // 使用pipeline批量写入Redis
            stringRedisTemplate.executePipelined((RedisCallback<Object>) connection -> {
                // 1. 写入有效用户（设置较长的过期时间）
                if (validUsersInRedis != null && !validUsersInRedis.isEmpty()) {
                    for (Map.Entry<String, Map> entry : validUsersInRedis.entrySet()) {
                        String key = entry.getKey();
                        Map<String, String> hashMap = entry.getValue();
                        // 将Map转换为byte[]格式
                        Map<byte[], byte[]> byteMap = convertToByteMap(hashMap);
                        // 执行hMSet
                        connection.hMSet(key.getBytes(), byteMap);
                        // 设置过期时间（有效用户1小时）
                        connection.expire(key.getBytes(),
                                TimeUnit.MINUTES.toSeconds(CaffeineConstants.USERINFO_CACHE_TTL_MINUTES));
                    }
                }

                // 2. 写入空值用户（设置较短的过期时间，防止缓存穿透）
                if (invalidUsersInRedis != null && !invalidUsersInRedis.isEmpty()) {
                    for (Map.Entry<String, Map> entry : invalidUsersInRedis.entrySet()) {
                        String key = entry.getKey();
                        Map<String, String> hashMap = entry.getValue();
                        // 将Map转换为byte[]格式
                        Map<byte[], byte[]> byteMap = convertToByteMap(hashMap);
                        // 执行hMSet
                        connection.hMSet(key.getBytes(), byteMap);
                        // 空值用户设置5分钟过期（短一点）
                        connection.expire(key.getBytes(), CaffeineConstants.USERINFO_CACHE_TTL_SECONDS); // 5分钟
                    }
                }
                return null;
            });

            log.info("批量写入Redis完成：有效用户{}个，空值用户{}个",
                    validUsersInRedis != null ? validUsersInRedis.size() : 0,
                    invalidUsersInRedis != null ? invalidUsersInRedis.size() : 0);

        } catch (Exception e) {
            log.error("批量写入Redis失败", e);
        }
    }
    private Map<String, String> convertUserinfoCacheToMap(UserinfoCache cache) {
        Map<String, String> map = new HashMap<>();
        map.put("id", String.valueOf(cache.getId()));
        map.put("nickName", cache.getNickName() != null ? cache.getNickName() : "");
        map.put("icon", cache.getIcon() != null ? cache.getIcon() : "");
        return map;
    }
    /**
     * 将Map<String, String>转换为Map<byte[], byte[]>
     */
    private Map<byte[], byte[]> convertToByteMap(Map<String, String> source) {
        Map<byte[], byte[]> target = new HashMap<>(source.size());
        source.forEach((k, v) -> {
            target.put(k.getBytes(StandardCharsets.UTF_8),
                    v != null ? v.getBytes(StandardCharsets.UTF_8) : "".getBytes(StandardCharsets.UTF_8));
        });
        return target;
    }
    public void asyncSaveInfoFromMysqlToCache(String redisKey, Long userId){
        User user = new User().setId(userId);
        UserinfoCache userinfoCache;
        try {
            user = userService.getById(userId);
            if(user == null){
                //存空值
                userinfoCache = new UserinfoCache(userId,"","");
            }
            else{
                UserInfo userInfo = userInfoService.getById(userId);
                String nickName = userInfo != null ? userInfo.getNickName() : "";
                String icon = userInfo != null ? userInfo.getIcon() : "";
                userinfoCache = new UserinfoCache(user.getId(), nickName, icon);
            }
            Map<String, Object> userInfoMap = BeanUtil.beanToMap(userinfoCache, new HashMap<>(),
                    CopyOptions.create().setIgnoreNullValue(true)
                            .setFieldValueEditor((fieldName, fieldValue) ->
                                    fieldValue != null ? fieldValue.toString() : ""));
            stringRedisTemplate.opsForHash().putAll(redisKey, userInfoMap);
            stringRedisTemplate.expire(redisKey, CaffeineConstants.USERINFO_CACHE_TTL_MINUTES, TimeUnit.MINUTES);
            userinfoCaffeine.put(redisKey,userinfoCache);
            log.info("已缓存用户{}", userinfoCache);
        } catch (Exception e) {
            log.error("数据库查询用户信息失败 :{}",e.getMessage());
        }

    }
}
