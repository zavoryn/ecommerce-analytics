# 数据仓库设计：分层架构 + OneID

## 1. 为什么需要数仓分层？

直接把 Kafka 原始数据写进 ClickHouse 查询有两个问题：

1. **数据质量差**：原始 JSON 里可能有脏数据（时间戳异常、作弊流量、字段缺失）
2. **查询成本高**：每次分析都要在 TB 级明细上重新聚合

分层的核心思想：**让数据越往下越干净、越聚合，查询的人只用看最下层**。

---

## 2. 四层数仓架构

```
                    ┌─────────────────┐
                    │   Kafka 原始流   │  ← 前端埋点/订单MQ
                    └────────┬────────┘
                             │ Flink 消费
                             ▼
 ┌──────────────────────────────────────────────────────┐
 │  ODS 层（Operational Data Store，原始数据层）         │
 │                                                      │
 │  存储：Hive（全量历史快照）                            │
 │  内容：原始 JSON，不做任何处理，原样保存               │
 │  作用：出问题时从这里重跑所有下游，类似"收据存根"        │
 │  分区：按天分区（dt=2025-01-15）                      │
 │                                                      │
 │  表：ods_user_event_log（原始埋点）                   │
 │       ods_order_log（原始订单）                       │
 └────────────────────┬─────────────────────────────────┘
                      │ Spark/Flink 清洗
                      ▼
 ┌──────────────────────────────────────────────────────┐
 │  DWD 层（Data Warehouse Detail，明细数据层）           │
 │                                                      │
 │  存储：ClickHouse（events_local + dwd_user_order_action）│
 │  内容：清洗后的结构化明细，JSON 字段拆平                 │
 │  处理：                                               │
 │    ① 过滤作弊流量（异常高频 device_id）                │
 │    ② 修正时间戳（不能早于 30 天前）                    │
 │    ③ 双流 Join：行为 + 订单 → 宽表                   │
 │    ④ 补充维度：item_id → 类目/品牌（维表广播）         │
 │                                                      │
 │  表：dwd_user_event（行为明细）                        │
 │       dwd_user_order_action（行为-订单宽表）  ← 核心  │
 └────────────────────┬─────────────────────────────────┘
                      │ Spark 每日凌晨聚合 / Flink 实时聚合
                      ▼
 ┌──────────────────────────────────────────────────────┐
 │  DWS 层（Data Warehouse Summary，汇总数据层）          │
 │                                                      │
 │  存储：ClickHouse（dws_item_daily）                   │
 │  内容：按维度聚合的指标，不含原始明细                   │
 │  粒度：按天 × 商品 / 按天 × 类目 / 按天 × 用户群       │
 │                                                      │
 │  表：dws_item_daily（商品每日 PV/UV/GMV）              │
 │       dws_category_daily（类目每日汇总）               │
 │       dws_funnel_daily（漏斗每日转化率）                │
 └────────────────────┬─────────────────────────────────┘
                      │ 直接写 or 从 DWS 再聚合
                      ▼
 ┌──────────────────────────────────────────────────────┐
 │  ADS 层（Application Data Store，应用数据层）          │
 │                                                      │
 │  存储：MySQL event_agg_daily + Redis 缓存              │
 │  内容：直接给业务接口用的最终指标                       │
 │  特点：数据量小，查询极快，前端直取                     │
 │                                                      │
 │  表：event_agg_daily（MySQL，供 Query 服务读取）        │
 │  缓存：Redis key=item:trend:{itemId}:{days}           │
 └──────────────────────────────────────────────────────┘
```

---

## 3. DWD 宽表设计（核心）

### 3.1 为什么需要宽表？

**问题**：运营要查"用户从搜索到支付的完整路径 + 对应的订单金额"

**如果没有宽表**：
```sql
-- MySQL：需要跨月分表 + 跨库 JOIN，极慢，可能 OOM
SELECT e.device_id, e.ts, o.order_amount
FROM event_detail_202501 e
JOIN order_sync o ON JSON_EXTRACT(e.ext_info,'$.order_id') = o.order_id
UNION ALL
SELECT ...FROM event_detail_202502 e ...  -- 跨月麻烦
```

**有了 DWD 宽表**：
```sql
-- ClickHouse：一张表，一行 = 用户一次完整购买链路
SELECT user_id, view_time, cart_time, pay_time, order_amount
FROM dwd_user_order_action
WHERE item_id = ? AND event_date BETWEEN ? AND ?
```

### 3.2 宽表字段设计

```sql
-- ClickHouse: dwd_user_order_action
CREATE TABLE dwd_user_order_action (
  event_date    Date,          -- 分区键
  user_id       UInt64,        -- 登录用户（0=匿名）
  device_id     String,        -- 设备指纹（全程携带）
  session_id    String,        -- 会话（漏斗串联）
  item_id       UInt64,
  category      LowCardinality(String),
  -- 行为时间戳（每一步都记）
  search_time   Nullable(DateTime),
  view_time     Nullable(DateTime),
  cart_time     Nullable(DateTime),
  pay_time      Nullable(DateTime),
  -- 订单信息（JOIN 过来的）
  order_id      UInt64,
  order_amount  Float64,
  order_status  Int8            -- 1已支付 2已取消
)
ENGINE = MergeTree()
ORDER BY (user_id, event_date, item_id);
```

### 3.3 如何关联行为与订单？（三种方案）

#### 方案 A：埋点直接携带 order_id（最简单，本项目实现）

前端支付成功时，埋点 properties 里带 order_id：
```json
{
  "event_name": "pay_order",
  "device_id": "abc123",
  "properties": { "item_id": 888, "order_id": 666 }
}
```
ClickHouse 直接有关联键，无需 JOIN。

**适合**：中小规模，前端配合好的场景。

#### 方案 B：Flink 双流 JOIN（大厂标准，本项目 bigdata 模块骨架）

```
Kafka Topic: ecom-user-events  (行为流)
Kafka Topic: TOPIC_ORDER_SYNC  (订单流)
                    ↓
              Flink Interval Join
              条件: user_id + item_id 相同
              窗口: 行为时间前后 2 小时内
                    ↓
              ClickHouse dwd_user_order_action
```

```java
// Flink 双流 Join 示意
behaviorStream
    .keyBy(e -> e.getUserId() + "_" + e.getItemId())
    .intervalJoin(orderStream.keyBy(o -> o.getUserId() + "_" + o.getItemId()))
    .between(Time.hours(-2), Time.hours(0))  // 行为在订单前 2h 内
    .process(new OrderBehaviorJoinFunc())
    .addSink(clickhouseSink);
```

**适合**：高并发大数据量，行为和订单必须关联分析。

#### 方案 C：ID-Mapping（解决未登录用户归因，见下节）

---

## 4. OneID / ID-Mapping（未登录用户全链路追踪）

### 4.1 问题描述

```
第1天：匿名浏览（device_id=abc123, user_id=null）
第3天：注册登录，下单（device_id=abc123, user_id=10086）

漏斗分析时：这算 1 个用户完整转化，还是 2 个独立行为？
```

**没有 OneID 的后果**：漏斗"浏览→购买"转化率严重低估，  
因为匿名浏览和登录购买被算成两个不同用户。

### 4.2 解决方案

#### Step 1：端侧设备指纹（device_id）

前端生成唯一设备 ID，存 localStorage，不随登录状态变化：
```javascript
// 简化版，生产用 Canvas 指纹 + 设备机器码
const deviceId = localStorage.getItem('device_id') 
    || (localStorage.setItem('device_id', uuid()), localStorage.getItem('device_id'));
```

所有埋点事件，无论是否登录，都携带 device_id。

#### Step 2：登录时双绑（服务端）

用户登录那一刻，前端发出的登录请求同时携带 device_id + user_id：
```java
// id_mapping 表：MySQL 存储
INSERT INTO id_mapping (device_id, user_id, bind_time)
VALUES (?, ?, NOW())
ON DUPLICATE KEY UPDATE bind_time = NOW();
```

#### Step 3：数仓层图计算归并（OneID）

每天凌晨，Spark GraphX 跑连通子图算法：

```
同一个 device_id 关联到多个 user_id（换设备登录）
同一个 user_id 关联到多个 device_id（多设备登录）
                    ↓
图的连通分量 = 同一个"真实用户"
                    ↓
给这个连通分量分配一个 one_id
                    ↓
历史所有匿名轨迹按 one_id 归并
```

```
例子：
  abc123(手机) → user_id=10086
  def456(电脑) → user_id=10086
  
  连通子图：{abc123, def456, 10086} → one_id=UID_X
  
  abc123 在第1天的匿名浏览，归并到 UID_X 的历史轨迹中
```

#### 4.3 面试话术

> "我们在前端生成持久化的 device_id，全程携带。用户登录时，后端把 device_id 和 user_id 双写进 id_mapping 表。数仓侧每天凌晨用 Spark 的连通子图算法，把同一个用户在多设备、登录前后的所有轨迹归并到一个 one_id 下，这样漏斗分析时就不会因为用户切换设备或登录状态而漏统计转化。"

---

## 5. 数据流完整时序图

```
用户操作:  搜索  →  浏览  →  加购  →  登录  →  支付
             ↓       ↓       ↓       ↓       ↓
前端埋点:  search  view   add_cart  login  pay_order
             ↓       ↓       ↓       ↓       ↓
Collector:    ←─── 全部带 device_id + session_id ───→
             ↓                       ↓
           Kafka                login事件写id_mapping
             ↓
           Flink 清洗
             ↓              ↓
         events_local   双流Join（行为+订单）
         (CK明细)            ↓
                     dwd_user_order_action
                         (CK宽表)
                             ↓
                     DWS 每日聚合
                             ↓
                     ADS 对外接口
```

---

## 6. T+1 vs 实时：什么时候用哪个？

| 分析需求 | 数据时效 | 走哪里 |
|---|---|---|
| 今天实时 GMV | 实时（< 1s 延迟） | Flink → CK dwd宽表 |
| 昨天商品 TOP10 | T+1 | DWS dws_item_daily |
| 近 30 天趋势 | T+1 | MySQL event_agg_daily |
| 历史漏斗转化 | T+1 | CK windowFunnel |
| 用户路径分析 | T+1 | Hive + Spark |
| 实时前 1h 销售 | 准实时 | CK events_local 直查 |
