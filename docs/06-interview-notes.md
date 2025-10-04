# 面试讲解要点 ↔ 代码位置对照表

> 这份文档帮助快速定位"面试时提到的某个技术点"对应项目里的哪个文件，
> 保持面试话术和代码细节的一致性。

---

## 一、埋点规范（面试问题 1.1）

**话术**：前端按 DRD 规范上报，分 common 公共参数和 properties 业务属性。

**代码位置**：
- `ecom-analytics-common/src/.../dto/UserEventDTO.java`
  - `deviceId` / `userId` / `sessionId` / `eventName` / `timestamp` → common 公共参数
  - `properties: Map<String,Object>` → 业务扩展属性（item_id/order_id/price...）
- `ecom-analytics-common/src/.../enums/EventType.java` → 事件名枚举标准化

---

## 二、全链路漏斗统计（面试问题 1.2）

**话术**：以 device_id 分组，ClickHouse windowFunnel 函数在 2h 窗口内匹配有序事件。

**代码位置**：
- `ecom-analytics-query/src/.../service/FunnelService.java`
  - `windowFunnel(7200)` SQL 完整写法，含注释说明
- `infra/clickhouse/init.sql` → `events_local` 表结构 + 查询示例注释
- `docs/02-data-warehouse.md` → 第3节 DWD 宽表，双流 Join 产出链路

---

## 三、PV/UV/GMV 统计口径（面试问题 1.3）

**话术**：PV=事件总数；UV=device_id COUNT DISTINCT，大促用 HLL 近似；GMV=order_amount含未支付。

**代码位置**：
- `infra/clickhouse/init.sql` → 示例2 `uniqHLL12(device_id)` UV 估算
- `ecom-analytics-processor/src/.../task/DailyAggregateTask.java`
  - `COUNT(DISTINCT device_id) AS uv` 精确去重聚合
- `infra/mysql/init.sql` → `event_agg_daily` 表的 pv/uv/gmv 字段说明

---

## 四、PB 级数据架构（面试问题 1.4）

**话术**：Kafka 削峰 → Flink 清洗 → ClickHouse 列存 OLAP，MySQL 只存核心交易。

**代码位置**：
- `ecom-analytics-bigdata/src/.../kafka/EventKafkaProducer.java` → Kafka 生产者
- `ecom-analytics-bigdata/src/.../flink/EventCleanJob.java` → Flink 作业骨架
- `infra/clickhouse/init.sql` → MergeTree 建表语句含 index_granularity=8192 注释
- `docs/01-architecture.md` → 第2节整体架构图

**关键数字**：
- Kafka 单机 100万 QPS vs RocketMQ 10万 QPS
- CK 稀疏索引每 8192 行一个 Granule
- TTL 180天自动清理

---

## 五、ES 倒排索引（面试问题 1.5）

**话术**：IK 分词 → FST 词典（内存）→ FOR 压缩倒排表 → Roaring Bitmap 联合查询。

**代码位置**：
- `ecom-analytics-search/src/.../repository/doc/ProductDoc.java`
  - `@Field(analyzer="ik_max_word", searchAnalyzer="ik_smart")` → IK 分词配置
- `ecom-analytics-search/src/.../service/ProductSearchService.java`
  - `BoolQuery` 多条件 → Roaring Bitmap 位运算
  - `search_after` 深分页完整实现
- `infra/elasticsearch/product_mapping.json` → Index Mapping 配置

---

## 六、埋点表无感扩展（面试问题 1.6）

**话术**：ext_info JSON 字段 + 虚拟列 Generated Column 建索引，避免 ALTER TABLE 锁表。

**代码位置**：
- `infra/mysql/init.sql` → `event_detail_202501` 表
  - `ext_info JSON` 字段
  - `item_id_vi BIGINT GENERATED ALWAYS AS (...)` 虚拟列
  - `KEY idx_ts_item (ts, item_id_vi)` 虚拟列索引

---

## 七、OneID / 未登录用户追踪（面试问题 1.7）

**话术**：device_id 全程携带，登录时双绑 id_mapping，数仓侧 Spark 图计算归并。

**代码位置**：
- `infra/mysql/init.sql` → `id_mapping` 表设计
- `docs/02-data-warehouse.md` → 第4节完整 OneID 方案说明
- `ecom-analytics-common/src/.../dto/UserEventDTO.java`
  - `deviceId` 字段注释说明未登录用户唯一凭证

---

## 八、数据采集兜底方案（面试问题 2.2）

**话术**：前端重试3次→localStorage暂存；后端写滚动日志→定时扫补；RocketMQ+定时拉双重兜底。

**代码位置**：
- `ecom-analytics-collector/src/.../service/EventCollectService.java`
  - `ACCESS_LOG.info(dto)` → 先写日志再发 MQ
- `ecom-analytics-collector/src/.../fallback/LocalBufferFallback.java`
  - 内存队列上限 10000，满则备份文件，每秒批量重发
- `ecom-analytics-collector/src/main/resources/logback-spring.xml`
  - `SizeAndTimeBasedRollingPolicy` 100MB切割，24h保留
- `ecom-analytics-processor/src/.../task/OrderPullTask.java`
  - 每5分钟定时拉单兜底

---

## 九、数据一致性（面试问题 2.4）

**话术**：行为+订单双流Join，先存临时表等待，都到了再写正式分析表，每小时扫补偿。

**代码位置**：
- `ecom-analytics-processor/src/.../consumer/UserEventConsumer.java` → 行为流消费
- `ecom-analytics-processor/src/.../consumer/OrderSyncConsumer.java` → 订单流消费
- `ecom-analytics-bigdata/src/.../flink/EventCleanJob.java` → Flink 双流 Join 骨架
- `infra/mysql/init.sql` → `join_temp_event` 临时表
- `ecom-analytics-processor/src/.../task/JoinTempScanTask.java` → 每小时扫补偿

---

## 十、消息队列可靠性（面试问题 2.5）

**话术**：持久化+手动ACK+最多重试3次+死信队列告警+指数退避防重试风暴。

**代码位置**：
- `ecom-analytics-processor/src/.../consumer/UserEventConsumer.java`
  - `@RocketMQMessageListener` → `ConsumeMode.ORDERLY`（有序消费）
  - 异常时 `throw e` → RocketMQ 框架按 maxReconsumeTimes 重试，超出进死信

---

## 十一、幂等性（面试问题 2.6）

**话术**：Redis SETNX requestId（5min）→ DB 唯一键 uk_idempotent，REPLACE INTO 兜底，Redis 宕机自动降级。

**代码位置**：
- `ecom-analytics-collector/src/.../service/IdempotentService.java`
  - `redis.opsForValue().setIfAbsent(KEY+requestId, "1", TTL_5min)`
  - catch 异常后 return true → 自动降级
- `ecom-analytics-processor/src/.../service/EventPersistService.java`
  - `REPLACE INTO` SQL → DB 层兜底
- `infra/mysql/init.sql` → `UNIQUE KEY uk_idempotent (request_id)`

---

## 十二、查询性能优化（面试问题 2.7）

**话术**：聚合表+联合索引（100ms）→ Redis缓存（1ms）→ 明细表补实时 → ES解决深分页。

**代码位置**：
- `ecom-analytics-query/src/.../service/TrendService.java`
  - 三段式查询逻辑：聚合表优先，今日补明细，`@Cacheable` Redis缓存
- `ecom-analytics-query/src/.../service/FunnelService.java`
  - ClickHouse windowFunnel 实现
- `ecom-analytics-search/src/.../service/ProductSearchService.java`
  - ES `search_after` 深分页完整实现
- `ecom-analytics-common/src/.../util/MonthlyTableUtil.java`
  - 跨月分表路由逻辑

---

## 十三、大促应急（面试问题 2.8）

**话术**：Sentinel 限流→LocalBufferFallback内存削峰→批量写→大促后补偿更新聚合表。

**代码位置**：
- `ecom-analytics-gateway/src/main/resources/application.yml`
  - `RequestRateLimiter` 令牌桶配置，replenishRate/burstCapacity
- `ecom-analytics-collector/src/.../fallback/LocalBufferFallback.java`
  - `CAPACITY=10000` 上限，满了写备份文件
- `ecom-analytics-query/src/.../controller/TrendController.java`
  - `@SentinelResource` 限流降级

---

## 十四、高性能接口设计（面试问题 2.10）

**话术**：聚合表+联合索引100ms内；Redis缓存挡并发；限流10次/min防刷；实时数据合并明细表。

**代码位置**：
- `ecom-analytics-query/src/.../service/TrendService.java` → 完整三段式逻辑
- `ecom-analytics-query/src/.../controller/TrendController.java`
  - `@SentinelResource` blockHandler 限流降级
  - `@Cacheable` 缓存注解
- `ecom-analytics-query/src/main/resources/application.yml`
  - `spring.cache.redis.time-to-live: 10m`

---

## 十五、运营大盘接口（面试问题：如何支撑运营数据需求）

**话术**：三大场景——GMV 概览（实时）、趋势折线图（聚合表+降级）、类目/榜单分析（T+1预计算）。

**代码位置**：
- `ecom-analytics-query/src/.../service/OperationService.java`
  - `overview()` → order_sync 精确口径 + CK 实时 UV
  - `gmvTrend(days)` → platform_daily 聚合表，缺数据时降级 order_sync GROUP BY
  - `categoryStats()` → category_daily（DailyAggregateTask Step2 填充）
- `ecom-analytics-query/src/.../controller/OperationController.java`
  - `GET /api/operation/overview` / `/gmv-trend` / `/category-stats`

---

## 十六、排行榜与选品（面试问题：热搜词/TOP商品怎么做）

**话术**：
- TOP商品：凌晨预计算 item_ranking_daily（ROW_NUMBER，含昨日排名对比），查询 < 5ms；
  无数据时降级 order_sync JOIN product_info 实时聚合。
- 热搜词：今日走 CK events_local 实时（秒级）；历史走 MySQL search_keyword_daily（T+1）。

**代码位置**：
- `ecom-analytics-query/src/.../service/RankingService.java`
  - `topItems()` → item_ranking_daily 预计算 → 降级实时聚合
  - `hotKeywords()` → CK 实时（今日）/ MySQL T+1（历史）
  - `topCategories()` → category_daily GROUP BY category
- `ecom-analytics-query/src/.../controller/RankingController.java`
  - `GET /api/ranking/top-items` / `/hot-keywords` / `/top-categories`
- `ecom-analytics-processor/src/.../task/DailyAggregateTask.java`
  - 五步聚合流水线（Step1~Step5），每步最多重试3次，失败打 ERROR 告警

---

## 十七、单商品转化漏斗（面试问题：某商品流量高GMV低怎么诊断）

**话术**：从 ClickHouse events_local WHERE item_id=? 做 windowFunnel，看每步转化率。
加购率 30% 远低均值 → 说明商品详情/价格/评价有问题，而非流量问题。

**代码位置**：
- `ecom-analytics-query/src/.../service/FunnelService.java`
  - `itemFunnel(itemId, fromDate, toDate)` → CK events_local WHERE item_id=?
- `ecom-analytics-query/src/.../controller/TrendController.java`
  - `GET /api/query/item-funnel?itemId=888&fromDate=...&toDate=...`

---

## 十八、新增表结构（面试问题：运营数据需要哪些表）

**代码位置**：`infra/mysql/init.sql`
- `product_info` — 商品目录，Canal 同步，供 JOIN 获取 title/brand
- `category_daily` — 类目日聚合：pv/uv/search_cnt/add_cart_cnt/pay_cnt/gmv
- `search_keyword_daily` — 热搜词日聚合：search_cnt/ctr/cvr
- `item_ranking_daily` — TOP100 榜单：rank_no/prev_rank_no（排名变化）/多维度
- `platform_daily` — 平台大盘：全站 pv/uv/gmv/pay_rate/arpu

**ClickHouse 新增**：`infra/clickhouse/init.sql`
- `dws_category_daily` — 类目实时汇总（SummingMergeTree）
- `dws_search_keyword_daily` — 搜索词实时汇总

---

## 快速定位命令

```bash
# 找幂等相关代码
grep -r "idempotent\|requestId\|SETNX\|REPLACE INTO" --include="*.java" .

# 找漏斗相关代码
grep -r "windowFunnel\|funnel\|Funnel" --include="*.java" .

# 找分表路由
grep -r "MonthlyTable\|event_detail_" --include="*.java" .

# 找兜底方案
grep -r "fallback\|LocalBuffer\|rollback\|compensate" --include="*.java" .
```
