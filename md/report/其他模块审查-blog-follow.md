# Blog + Follow 模块审查报告

> 审查日期：2026-06
> 审查人：架构毁灭者 · 代码挑刺专家
> 审查范围：BlogServiceImpl, BlogController, FollowServiceImpl

---

### 🟠 【P1 - 性能线】`setUserToBlog()` 在列表中循环查库——N+1 查询

- **问题描述**：`BlogServiceImpl.setUserToBlog()` 对每个博客单独调用 `userService.getById(userId)`：
  ```java
  User user = userService.getById(userId);
  blog.setName(user.getNickName());
  blog.setIcon(user.getIcon());
  ```
  该函数在 `queryHotById()`、`queryBlogOfFollow()` 等列表查询中被循环调用。
- **潜在风险**：列表页返回 10 条博客 → 额外 10 次 `SELECT * FROM tb_user WHERE id=?`。虽然没有 JOIN，但 10 次独立查库的网络往返时间叠加，接口响应随列表长度线性增长。
- **修改建议**：批量查询用户。一次性查出所有 userId → 组装 Map，内存填充：

```java
private void setUserToBlog(List<Blog> blogs) {
    Set<Long> userIds = blogs.stream().map(Blog::getUserId).collect(Collectors.toSet());
    List<User> users = userService.listByIds(userIds);
    Map<Long, User> userMap = users.stream().collect(Collectors.toMap(User::getId, u -> u));
    blogs.forEach(blog -> {
        User u = userMap.get(blog.getUserId());
        if (u != null) {
            blog.setName(u.getNickName());
            blog.setIcon(u.getIcon());
        }
    });
}
```

---

### 🟠 【P1 - 可用性线】`likeBlog()` 先更新 DB 再写 Redis，Redis 失败后状态不一致

- **问题描述**：`BlogServiceImpl.likeBlog()`：
  ```java
  boolean update = update().setSql("liked = liked + 1").eq("id", id).update();
  if (update) {
      stringRedisTemplate.opsForZSet().add(RedisConstants.BLOG_LIKED_KEY + id, userId.toString(), ...);
  }
  ```
  DB 更新成功 → Redis ZAdd 失败（网络超时/Redis 宕机） → DB 中 liked 已 +1，但 Redis 未记录该用户已点赞 → 用户再次点击"点赞"，Redis 查不到 → DB liked 再 +1。**一次点赞变成两次**。
- **修改建议**：先写 Redis（本地操作，极快），Redis 成功后再更新 DB，DB 失败时回滚 Redis：

```java
// 先操作 Redis（快速，低失败率）
stringRedisTemplate.opsForZSet().add(key, userId.toString(), System.currentTimeMillis());
// 再更新 DB（慢，可能失败）
boolean update = update().setSql("liked = liked + 1").eq("id", id).update();
if (!update) {
    // DB 回滚
    stringRedisTemplate.opsForZSet().remove(key, userId.toString());
}
```

或者更彻底：**异步 DB 更新**，Redis 作为权威数据源。

---

### 🟡 【P2 - 腐化线】粉丝 Feed 推送逐条写 Redis——大量粉丝时性能灾难

- **问题描述**：`BlogServiceImpl.saveBlog()`：
  ```java
  List<Follow> followsUserId = followService.query().eq("follow_user_id", user).list();
  for (Follow follow : followsUserId) {
      stringRedisTemplate.opsForZSet().add("feed:" + follow.getUserId(), blog.getId().toString(), ...);
  }
  ```
  每次发博客，逐条 for 循环写 Redis。已有批量的 `pushBloToFansBatch()` 方法（使用 Pipeline）但被**注释掉**了。
- **触发场景**：大 V 发一条博客 → 10 万粉丝 → 10 万次 Redis 网络往返 → 接口超时。
- **修改建议**：启用 `pushBloToFansBatch()` Pipeline 方法替代循环，或者异步推送（MQ）。

---

### 🟡 【P2 - 腐化线】`saveBlog()` 无事务——Redis 推送失败后粉丝看不到新博客

- **问题描述**：`saveBlog()` 没有 `@Transactional`。博客保存到 DB 成功后，Redis 推送过程中出错（粉丝列表大导致超时/Redis 连接池耗尽），博客已保存但粉丝的 Feed 中看不到。
- **修改建议**：加 `@Transactional(rollbackFor = Exception.class)`，并用 MQ 异步推送 Feed，不阻塞主流程。

---

### 🟡 【P2 - 腐化线】`follow()` DB 和 Redis 操作不在同一事务——数据不一致

- **问题描述**：`FollowServiceImpl.follow()` 关注流程：
  ```java
  boolean isSuccess = save(follow);           // DB 写入
  if (isSuccess) {
      stringRedisTemplate.opsForSet().add(key, id.toString());  // Redis 写入
  }
  ```
  DB 成功 → Redis 失败 → DB 显示已关注，Redis Set 中没有 → `queryCommonFollow()`（依赖 Redis Set intersect）查不到 → 共同关注功能漏数据。
- **修改建议**：先写 Redis（低风险），再写 DB。或采用补偿机制：共同关注查询时以 DB 为准，Redis 只做加速。

---

### 🟡 【P2 - 腐化线】`queryBlogOfFollow()` 和 `queryUserList()` 存在 SQL 注入风险

- **问题描述**：两处使用 `last("order by field (id," + idStr + ")")` 拼接 SQL。虽然 `idStr` 当前来自 Redis ZSet（非用户输入），但：
  - 如果 Redis 数据被污染，恶意内容直接进入 SQL 语句
  - 这是典型的 **MyBatis-Plus `last()` 注入模式**
- **修改建议**：MyBatis-Plus 的 `last()` 无法参数化，建议改用循环查询或 `listByIds()` 后在 Java 中排序。

---

### 🔵 【P3 - 优雅度】

| 问题 | 位置 | 原因 |
|------|------|------|
| `queryUserList()` 中 `List<Long> userIds = new ArrayList<>();` 然后下一行立即重新赋值 | BlogServiceImpl:127-128 | 无用初始化 |
| `.in("id", userDTOList)` 传入 `Set<String>` 而非 `List<Long>` | BlogServiceImpl:132 | 类型不匹配，MyBatis-Plus 内部做转换增加开销 |
| 常量 `"follows:"` 硬编码 3 处 | FollowServiceImpl:49,57,67 | 应抽取为 RedisConstants 常量 |
| `setUserToBlog` 不支持 blog 为 null | BlogServiceImpl:78 | blog.getUserId() 没有空判断 |
