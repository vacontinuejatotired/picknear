基于 langfuse 抓取调用 agent 产生的耗时记录（格式化为调用树，单位全部为秒；chat 行右侧 `N → M (∑ K)` 为 输入token → 输出token (总计)）

```
agent.session                                                  14.73s  (5 items)
├─ agent.prompt_hook                                       0.02s
├─ agent.prompt.agent.system.main                          0.76s
├─ agent.phase1                                            1.69s  (2 items)
│  ├─ chat qwen-plus-2025-07-28                          1.40s
│  └─ agent.decision                                     0.01s
├─ agent.round.1                                           11.39s  (2 items)
│  ├─ agent.plan                                         3.19s  (10 items)
│  │  ├─ agent.prompt.agent.tool.query-published-blogs 0.13s
│  │  ├─ agent.prompt.agent.tool.publish-test-blog     0.13s
│  │  ├─ agent.prompt.agent.tool.query-blogs-by-title  0.12s
│  │  ├─ agent.prompt.agent.tool.query-total-blogs     0.46s
│  │  ├─ agent.prompt.agent.tool.query-total-users     0.13s
│  │  ├─ agent.prompt.agent.tool.query-total-shops     0.12s
│  │  ├─ agent.prompt.agent.tool.query-weather         0.12s
│  │  ├─ agent.prompt.agent.system.planner             0.12s
│  │  ├─ agent.prompt.agent.prompt.planner             0.12s
│  │  └─ chat qwen-plus-2025-07-28                     1.60s  [2,631 → 33 (∑ 2,664)]
│  └─ agent.subagent                                     8.19s  (7 items)
│     ├─ agent.prompt.agent.prompt.subagent.execution    0.13s
│     ├─ agent.prompt.agent.system.subagent              0.12s
│     ├─ subagent-chat qwen-plus-2025-07-28              0.87s  [1,001 → 17 (∑ 1,018)]
│     ├─ tool_call query-published-blogs                 0.22s  (1 item)
│     │  └─ agent.guard.-a-l-l-o-w.query-published-blogs 0.21s
│     ├─ subagent-chat qwen-plus-2025-07-28              0.66s  [3,045 → 36 (∑ 3,081)]
│     ├─ tool_call query-weather                         0.01s  (1 item)
│     │  └─ agent.guard.-a-l-l-o-w.query-weather       0.01s
│     └─ subagent-chat qwen-plus-2025-07-28              6.12s  [5,131 → 425 (∑ 5,556)]
└─ agent.round.2                                           0.78s  (1 item)
   └─ agent.plan                                           0.77s  (3 items)
      ├─ agent.prompt.agent.system.planner                 0.00s
      ├─ agent.prompt.agent.prompt.planner                 0.00s
      └─ chat qwen-plus-2025-07-28                         0.70s  [2,652 → 9 (∑ 2,661)]
```

[参考链路的langfuse公开访问url,可能加载有些慢，访问失败可能是数据过期了，可参考下面那个文件](https://jp.cloud.langfuse.com/project/cmscnp4n40007ad0d81wlodt4/traces/5e2f66f2bf1c8444971ceff837235474?observation=86d1e8db60be2523)

[参考链路导出json文件](.\json\trace-5e2f66f2bf1c8444971ceff837235474.json)

## 1.按需加载工具提示词 ##

前情提要：输入为：查看我的博客以及长沙天气

输出为：
我来查一下我已为您查询相关信息：
您当前发布的博客共有7篇，按点赞数排序，其中点赞最多的是标题为" d"的博客，内容为"是S"，获得1个点赞。其他博客包括《战地六促销，打北约的来》《claude又不可用了》《苍穹外卖》等，内容涉及游戏、编程和日常分享。
另外，长沙当前天气为晴天，适合外出活动。

一上来就可以看到一个痛点，主模型规划阶段貌似加载了已有的所有工具提示词，每一个加载都占了0.12s左右(耗时0.12s是http请求的问题，首次需要走http请求拉取，后续依赖缓存会在毫秒内获取提示词)只是幸好本项目做的tool不多，数量一大的话，每次加载工具的提示词就需要几秒，所以打算把原先的全量加载改为按需加载，按需加载既少浪费token，又避免ai犯蠢做错选择，可是问题又来了，怎么按需呢？关键词匹配？还是ai自己决定？

选型有rag，轻量llm路由，关键词匹配，工具promopt压缩
最终选择轻量llm路由+压缩，原因如下：
rag都知道是用向量选只接近的值，不过目前这个项目用这个未免太重了，工具量大了可以考虑
关键词匹配又太幽默了，用户需求一模糊不就匹配不上了
llm可以起到一个识别意图，天生比关键词匹配好，其次和rag比起来足够轻量
前置添加一个压缩也可以起到省token的作用，如果想要提高精准度，那需要做舍取了（快和准只能选一边）


优化后的测试情况


```
agent.session                                                  16.61s  (5 items)
├─ agent.prompt_hook                                       0.01s
├─ agent.prompt.agent.system.main                          0.69s
├─ agent.phase1                                            1.23s  (2 items)
│  ├─ chat qwen-plus-2025-07-28                          0.99s
│  └─ agent.decision                                     0.01s
├─ agent.round.1                                           13.74s  (2 items)
│  ├─ agent.plan                                         3.58s  (3 items)
│  │  ├─ agent.prompt.agent.system.planner             0.13s
│  │  ├─ agent.prompt.agent.prompt.planner             0.10s
│  │  └─ chat qwen-plus-2025-07-28                     3.20s  [2,903 → 33 (∑ 2,936)]
│  └─ agent.subagent                                     10.16s  (9 items)
│     ├─ agent.prompt.agent.prompt.subagent.execution    0.12s
│     ├─ agent.prompt.agent.system.subagent              0.11s
│     ├─ agent.prompt.agent.tool.query-published-blogs   0.12s
│     ├─ agent.prompt.agent.tool.query-weather           0.12s
│     ├─ subagent-chat qwen-plus-2025-07-28              0.56s  [1,001 → 17 (∑ 1,018)]
│     ├─ tool_call query-published-blogs                 0.17s  (1 item)
│     │  └─ agent.guard.-a-l-l-o-w.query-published-blogs 0.16s
│     ├─ subagent-chat qwen-plus-2025-07-28              1.52s  [3,045 → 36 (∑ 3,081)]
│     ├─ tool_call query-weather                         0.01s  (1 item)
│     │  └─ agent.guard.-a-l-l-o-w.query-weather       0.01s
│     └─ subagent-chat qwen-plus-2025-07-28              7.37s  [5,131 → 426 (∑ 5,557)]
└─ agent.round.2                                           0.83s  (1 item)
   └─ agent.plan                                           0.83s  (3 items)
      ├─ agent.prompt.agent.system.planner                 0.00s
      ├─ agent.prompt.agent.prompt.planner                 0.00s
      └─ chat qwen-plus-2025-07-28                         0.76s  [2,935 → 9 (∑ 2,944)]
```
显然只读了相关的工具提示词

[参考链路url](https://jp.cloud.langfuse.com/project/cmscnp4n40007ad0d81wlodt4/traces/c6e9c299e1f2c3fc891521b91333ecb7?observation=4946c14ba2c75042)

[参考链路json文件](.\json\trace-c6e9c299e1f2c3fc891521b91333ecb7.json)
## 2.上下文传递优化 ##

![包括了输入token转换成输出token的截图](./img/image1.png)

仍然基于原有的这个观测树来分析，这里ai模型下耗时右边的两个数字从左往右分别代表输入token和输出token

第一次调用ai