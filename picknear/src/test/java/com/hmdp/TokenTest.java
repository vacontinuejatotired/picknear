package com.hmdp;
import com.baomidou.mybatisplus.core.toolkit.CollectionUtils;
import com.hmdp.voucher.entity.SeckillVoucher;
import com.hmdp.shop.entity.Shop;
import com.hmdp.shop.service.impl.ShopServiceImpl;
import com.hmdp.utils.TokenTestUtil;
import com.hmdp.utils.security.JwtUtil;
import com.mysql.cj.MysqlConnection;
import com.mysql.cj.jdbc.MysqlDataSource;
import io.micrometer.core.ipc.http.HttpSender;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;

import jakarta.annotation.Resource;
import java.net.http.HttpRequest;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@SpringBootTest(properties = {
        "spring.config.name=application,application-dev"
})
@ActiveProfiles("dev")
@Slf4j
public class TokenTest {
    static {
        try {
            Logger logger = LoggerFactory.getLogger("debug-test");
            logger.info("SLF4J 初始化测试成功");
        } catch (Throwable t) {
            System.err.println("SLF4J 初始化失败: " + t.getClass().getName());
            t.printStackTrace();
        }
    }
    @Resource
    private RedisTemplate stringRedisTemplate;

    @Resource
    private ShopServiceImpl shopService;
    @Resource
    private TokenTestUtil tokenTestUtil;

    private final JwtUtil jwtUtil = new JwtUtil(new DefaultResourceLoader());

    private static final List<String> TOKEN_LIST = new ArrayList<>();
    private static final List<String> PHONE_LIST = new ArrayList<>();
//
//    @Autowired
//    private Environment env;
    @Autowired
    private RedisConnectionFactory redisConnectionFactory;
//
//    @Test
//    void testRedisConnection() {
//        // 只是为了触发连接
//        redisConnectionFactory.getConnection().ping();
//    }
//
//    @Test
//    public void testGenerateJwt() throws InterruptedException {
//        String token = jwtUtil.generateToken(1L,10L, ChronoUnit.SECONDS);
//        System.out.println(token);
//        Map <String,String> map = new HashMap<>();
//        map.put("authorization",token);
//        Thread.sleep(10000L);
//        HttpHeaders headers = new org.springframework.http.HttpHeaders();
//        HttpEntity<String> entity = new HttpEntity<>(headers);
//    }
//
//    /**
//     * 生成指定数量的测试token并导出CSV
//     */
//    @Test
//    public void testRedisConnection() {
//        try {
//            stringRedisTemplate.opsForValue().set("test-key", "test-value", 1, TimeUnit.MINUTES);
//            String value = stringRedisTemplate.opsForValue().get("test-key").toString();
//            System.out.println("Redis测试成功，获取值: " + value);
//            assert "test-value".equals(value);
//        } catch (Exception e) {
//            System.err.println("Redis连接失败:");
//            e.printStackTrace();
//        }
//    }
//    @Test void printRedisConfig() { System.out.println(env.getProperty("spring.data.redis.host")); }
//   @Test
//    @Test
//    public void testExportToken() {
//        userService.testGenerateTokens(1000,"1000token.txt");
//   }

   @Test
   public void testExportToken() {
        tokenTestUtil.exportTokenAndRefreshTokenToCsv(1500,"tokenAndRefreshToken");
   }
//
//    @Test
//    public void loadShopData() {
//        log.info("开始加载店铺地理位置数据到 Redis");
//
//        // 1. 查询所有店铺
//        List<Shop> shops = shopService.list();
//        log.info("从数据库查询到店铺总数: {}", shops.size());
//
//        if (CollectionUtils.isEmpty(shops)) {
//            log.warn("店铺列表为空，本次不进行任何 Redis 操作");
//            return;
//        }
//
//        // 2. 按类型分组
//        String keyPrefix = "shop:geo:";
//        Map<Long, List<Shop>> typeGroupMap = shops.stream()
//                .collect(Collectors.groupingBy(Shop::getTypeId));
//
//        log.info("店铺按类型分组完成，共 {} 个类型", typeGroupMap.size());
//
//        // 3. 逐个类型写入 Redis GEO
//        int totalAdded = 0;
//        for (Map.Entry<Long, List<Shop>> entry : typeGroupMap.entrySet()) {
//            Long typeId = entry.getKey();
//            List<Shop> shopList = entry.getValue();
//
//            String geoKey = keyPrefix + typeId;
//
//            // 构建 GeoLocation 集合
//            List<RedisGeoCommands.GeoLocation<String>> geoLocations = new ArrayList<>(shopList.size());
//            for (Shop shop : shopList) {
//                if (shop.getX() == null || shop.getY() == null) {
//                    log.warn("店铺 id={} 坐标为空(typeId={})，跳过", shop.getId(), typeId);
//                    continue;
//                }
//
//                geoLocations.add(new RedisGeoCommands.GeoLocation<>(
//                        shop.getId().toString(),
//                        new Point(shop.getX(), shop.getY())
//                ));
//            }
//
//            // 执行添加
//            if (!geoLocations.isEmpty()) {
//                Long addedCount = stringRedisTemplate.opsForGeo().add(geoKey, geoLocations);
//                totalAdded += (addedCount != null ? addedCount : 0);
//
//                log.info("类型 {} 添加完成，key={}, 添加数量: {}, 当前累计: {}",
//                        typeId, geoKey, addedCount, totalAdded);
//            } else {
//                log.info("类型 {} 无有效坐标数据，跳过写入，key={}", typeId, geoKey);
//            }
//        }
//
//        log.info("店铺地理位置数据加载完成，总计写入 Redis 有效坐标点: {}", totalAdded);
//    }

//   @Test
//    public void testHyperLongLog(){
//        String keyPrefix = "hyper:longlog:";
//        int j=0;
//        String []values=new String[1000];
//        for (int i = 0; i < 1000000; i++) {
//            j= i%1000;
//            values[j]="user_"+String.valueOf(i);
//            if(j==999){
//                stringRedisTemplate.opsForHyperLogLog().add("hlog_test",values);
//            }
//        }
//       System.out.println(stringRedisTemplate.opsForHyperLogLog().size("hlog_test"));
//   }
}