//package com.hmdp;
//
//import com.hmdp.entity.VoucherOrder;
//import com.hmdp.service.impl.ShopServiceImpl;
//import com.hmdp.utils.constants.RabbitMqConstants;
//import com.hmdp.utils.RedisIdWorker;
//import org.junit.jupiter.api.Test;
//import org.springframework.amqp.rabbit.core.RabbitTemplate;
//import org.springframework.boot.test.context.SpringBootTest;
//
//import jakarta.annotation.Resource;
//import java.time.LocalDateTime;
//import java.util.HashMap;
//import java.util.Map;
//import java.util.concurrent.CountDownLatch;
//import java.util.concurrent.ExecutorService;
//import java.util.concurrent.Executors;
//
//@SpringBootTest
//class PickNearApplicationTests {
//
//@Resource
//    private ShopServiceImpl shopService;
//@Resource
//private RedisIdWorker redisIdWorker;
//private ExecutorService executorService = Executors.newFixedThreadPool(500);
//@Resource
//private RabbitTemplate rabbitTemplate;
//@Test
//void testIdWork() throws InterruptedException {
//    CountDownLatch countDownLatch = new CountDownLatch(300);
//    Runnable task = () -> {
//        for (int i = 0; i < 100; i++) {
//            long id= redisIdWorker.nextId("order");
//            System.out.println("id"+id);
//        }
//        countDownLatch.countDown();
//    };
//    long begin = System.currentTimeMillis();
//    for (int i=0;i<300;i++){
//    executorService.submit(task);
//    }
//    countDownLatch.await();
//    long end = System.currentTimeMillis();
//    System.out.println("time:"+(end-begin));
//}
//@Test
//    void testSaveShopRedis() {
//    shopService.saveShopRedis(1L,30L);
//}
//
//@Test
//    void createToken() {
//    for (int i = 0; i < 1000; i++) {
//
//    }
//}
//    @Test
//    public void testSendMessage() {
//        try {
//
//            VoucherOrder order = new VoucherOrder();
//            order.setId(999L);
//            order.setUserId(1001L);
//            order.setVoucherId(888L);
//            order.setCreateTime(LocalDateTime.now());
//            order.setStatus(1);
//
//            rabbitTemplate.convertAndSend(RabbitMqConstants.DEAD_EXCHANGE_NAME, RabbitMqConstants.DEAD_ROUTING_KEY, order);
//            System.out.println("测试3: VoucherOrder发送成功");
//
//        } catch (Exception e) {
//            System.err.println("发送失败: " + e.getMessage());
//            e.printStackTrace();
//        }
//    }
//}
