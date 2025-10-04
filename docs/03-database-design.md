# 数据库设计说明

## 1. 存储选型一览

| 存储 | 版本 | 用途 | 数据规模 |
|---|---|---|---|
| MySQL 8 | 8.0 | 核心交易、聚合结果、对账报告 | GB 级 |
| ClickHouse | 24.x | 埋点行为明细、DWD宽表、DWS汇总 | TB~PB 级 |
| Elasticsearch | 8.x | 商品搜索、订单运营后台检索 | 亿级文档 |
| Redis 7 | 7.x | 热点缓存、幂等 requestId、分布式锁 | 内存级 |

---

## 2. MySQL 表设计

### 2.1 核心表关系

```
                          ┌─────────────────────────────────┐
event_detail_YYYYMM ───→  │  event_agg_daily（商品维度聚合）  │
（行为明细，按月分表）      └──────────────┬──────────────────┘
                                          │
                          ┌───────────────▼──────────────────────────────┐
                          │  Step2: category_daily（类目维度聚合）         │
                          │  Step4: item_ranking_daily（TOP榜预计算）      │
                          │  Step5: platform_daily（平台整体大盘）         │
                          └──────────────────────────────────────────────┘

event_detail_YYYYMM ───→  search_keyword_daily（热搜词每日聚合，Step3）

order_sync ─────────────→ item_ranking_daily（JOIN 取 GMV/支付数据）
                     ───→ platform_daily（JOIN 取整体订单指标）

product_info ───────────→ item_ranking_daily（冗余商品名/品牌，避免查询JOIN）
（商品目录，Canal同步）

id_mapping                data_verify_report
（OneID设备-用户映射）     （每日对账报告）

join_temp_event
（双流Join等待区）
```

**凌晨聚合五步流水线（DailyAggregateTask）**：
```
00:30 触发
  Step1 event_agg_daily     ← event_detail_YYYYMM + order_sync + product_info
  Step2 category_daily      ← event_agg_daily（按类目 GROUP BY）
  Step3 search_keyword_daily ← event_detail WHERE event_name='search'
  Step4 item_ranking_daily  ← event_agg_daily + order_sync + product_info（TOP 100）
  Step5 platform_daily      ← event_agg_daily + order_sync（全平台汇总）
```

### 2.2 分表策略（面试稿 2.7）

```
event_detail_202501   event_detail_202502   event_detail_202503 ...
       ↑                      ↑                      ↑
  按月分表，每月约几百万行，单表不超过 500MB

路由规则（MonthlyTableUtil.java）：
  tableOf(LocalDateTime)  →  "event_detail_" + yyyyMM

跨月查询处理：
  1. 时间范围 → 计算涉及的表名列表
  2. Service 层对每个表分别查询
  3. 内存合并排序（数据量小时）
  4. 数据量大时 → 走 ES search_after（运营后台场景）
```

### 2.3 索引设计（面试稿 2.7 追问：索引影响写入？）

```sql
-- event_detail_YYYYMM 索引
UNIQUE KEY uk_idempotent (request_id)          -- 幂等，写入时校验
KEY idx_ts_item (ts, item_id_vi)               -- 覆盖"时间段内某商品"高频查询
KEY idx_device_ts (device_id, ts)              -- 覆盖"某设备的事件轨迹"
KEY idx_session (session_id)                   -- 漏斗链路串联

-- event_agg_daily 索引
UNIQUE KEY uk_date_item (event_date, item_id)  -- 聚合唯一键
KEY idx_item_date (item_id, event_date)        -- 覆盖商品趋势查询（主力索引）
```

**为什么只建这几个？**
- 索引 = 读加速但写变慢（每次写都要维护 B+ 树）
- 明细表写入 QPS 高，索引数量控制在 3 个以内
- 低频查询（如按地区）走 Redis 缓存结果，不建索引

### 2.4 JSON 虚拟列（面试稿 1.6 无感扩展）

```sql
-- 扩展字段存 JSON，避免 ALTER TABLE 锁表
`ext_info`    JSON      -- {"item_id":888, "order_id":666, "stay_time":30}

-- 为常用字段建虚拟列 + 索引，查询性能 ≈ 普通列
`item_id_vi`  BIGINT GENERATED ALWAYS AS (
                CAST(ext_info->>'$.item_id' AS UNSIGNED)
              ) VIRTUAL,
KEY `idx_ts_item` (`ts`, `item_id_vi`)   -- 虚拟列上可以建索引
```

---

## 3. ClickHouse 表设计

### 3.1 MergeTree 引擎选择

| 表 | 引擎 | 原因 |
|---|---|---|
| events_local | MergeTree | 通用明细，按 device_id 排序支持 windowFunnel |
| dwd_user_order_action | MergeTree | 宽表，按 user_id 排序 |
| dws_item_daily | SummingMergeTree | 自动累加，insert 幂等（多次聚合自动合并） |
| funnel_agg_state | AggregatingMergeTree | 物化视图存储聚合状态 |

### 3.2 稀疏索引原理（面试稿 1.4.4）

```
events_local ORDER BY (device_id, event_date, event_time)
                              ↓
每 8192 行提取一个索引条目（Granule）
索引文件极小（完全放内存）

查询 WHERE device_id='abc123' AND event_date='2025-01-15':
  → 内存中二分查找索引 → 定位 Granule 范围 → 只读目标 Granule
  → 不需要读整张表
```

### 3.3 分区 + TTL

```sql
-- 按月分区（PARTITION BY toYYYYMM(event_date)）
-- 好处：查询时可跳过不相关分区，DROP PARTITION 删老数据极快

-- TTL：180天自动清理（免手动维护）
TTL event_date + INTERVAL 180 DAY
```

### 3.4 windowFunnel 漏斗查询（面试稿 1.2）

```sql
-- 查 7 天内，2小时窗口的四步漏斗转化
SELECT
    level,
    count() AS user_cnt
FROM (
    SELECT
        device_id,
        windowFunnel(7200)(   -- 时间窗口 7200s = 2h
            event_time,
            event_name = 'search',     -- 第1步
            event_name = 'view_item',  -- 第2步
            event_name = 'add_cart',   -- 第3步
            event_name = 'pay_order'   -- 第4步
        ) AS level             -- 最大匹配步数（0=没匹配，4=全部完成）
    FROM events_local
    WHERE event_date BETWEEN '2025-01-08' AND '2025-01-15'
    GROUP BY device_id
)
GROUP BY level
ORDER BY level DESC;
-- level=4 → 走完全程的人数（分母:level=1 搜索人数）
```

---

## 4. ES 索引设计

### 4.1 为什么运营后台走 ES 而不是 MySQL？

```
场景：运营查"1月15日，已支付，类目=女装，金额>200"的订单，并按时间倒序翻到第500页

MySQL 的问题：
  - 跨月分表 UNION ALL → 应用层合并 10000 条 → 内存排序 → 返回第 500 页 → OOM 风险
  - 每次翻页都要重新扫大量数据

ES 的解法：
  - 倒排索引：多字段 AND 过滤走 Roaring Bitmap 位运算，毫秒级
  - search_after 游标：传上一页最后一条的 [orderTime, orderId]
    → 只拉当前页 20 条 → 无论第几页，性能恒定
```

### 4.2 search_after 深分页原理

```
普通分页（LIMIT 10000, 20）:
  ES 从每个分片拿出 10020 条 → 汇总 → 排序 → 丢弃前 10000 → 返回 20 条
  问题：数据量随页数线性增长，翻到深页极慢

search_after:
  第 N 页：传入第 N-1 页最后一条的 sort 值 [orderTime=2025-01-15 10:30:00, orderId=999]
  ES 直接从这个游标位置开始取 20 条
  问题：不能跳页（不支持"直接跳到第500页"），只能顺序翻
  解决：运营后台不支持任意跳页，只支持"上一页/下一页"
```

### 4.3 Canal 同步 MySQL → ES

```
MySQL order_sync 表
        ↓ binlog（binlog_format=ROW）
    Canal Server
        ↓ 推送变更消息
    OrderCanalSyncService
        ↓
    ES order_event_index
（准实时，延迟 < 1s）
```

---

## 5. 新增运营分析表说明

### 5.1 product_info（商品基础信息）

```sql
item_id, title, category, sub_category, brand,
price, cost_price, status, sales30d, stock,
created_at, updated_at
```

- **来源**：Canal 监听商品系统 MySQL binlog，实时同步到本库 + ES（product_index）
- **用途**：TOP 商品榜 JOIN 获取 item_name/brand；避免每次查询都请求商品服务

### 5.2 category_daily（类目每日聚合）

```sql
event_date, category,
pv, uv, search_cnt, add_cart_cnt, create_order_cnt,
pay_cnt, pay_amount, gmv, item_cnt
```

- **来源**：DailyAggregateTask Step2 从 event_agg_daily 按 category GROUP BY
- **用途**：运营大盘类目对比、GMV 占比饼图、加购率/转化率横向比较

### 5.3 search_keyword_daily（热搜词每日聚合）

```sql
event_date, keyword,
search_cnt, uv, click_cnt, pay_cnt, pay_amount,
ctr（点击率）, cvr（转化率）
```

- **来源**：DailyAggregateTask Step3 提取 event_detail WHERE event_name='search' 中的 ext_info.keyword
- **用途**：T+1 热词排行、CTR/CVR 分析；今日实时走 ClickHouse events_local

### 5.4 item_ranking_daily（商品排行榜）

```sql
rank_date, rank_type(gmv/pay_cnt/pv/add_cart_cnt),
category(NULL=全类目), rank_no, prev_rank_no,
item_id, item_name, brand, item_category,
pv, uv, add_cart_cnt, pay_cnt, pay_amount, gmv
```

- **来源**：DailyAggregateTask Step4 计算，ROW_NUMBER() OVER (ORDER BY ?) 生成排名
- **用途**：运营选品 TOP 榜，含排名变化（↑2/↓1/新上榜/持平）
- **查询速度**：< 5ms（完全走预计算结果，直接读取）

### 5.5 platform_daily（平台整体大盘）

```sql
event_date,
pv, uv, new_user_cnt, search_cnt, add_cart_cnt,
create_order_cnt, pay_order_cnt, pay_user_cnt,
pay_amount, gmv, refund_cnt, refund_amount,
cart_rate, order_rate, pay_rate, arpu
```

- **来源**：DailyAggregateTask Step5 汇总 event_agg_daily + order_sync
- **用途**：运营首页 GMV 趋势折线图、周报/月报数据来源
- **UV 说明**：此表 uv 为商品维度 UV 累加（近似值），精确全站 UV 来自 ClickHouse `uniq(device_id)`

---

## 6. Redis 使用场景

| Key 规范 | TTL | 用途 |
|---|---|---|
| `collect:req:{requestId}` | 5min | 幂等：防重复埋点 |
| `itemTrend::{itemId}:{days}` | 10min | 商品趋势接口缓存 |
| `funnel::{fromDate}:{toDate}` | 30min | 漏斗分析缓存 |
| `lock:insert:{uniqueKey}` | 1s | 分布式锁（SETNX） |
| `gateway:ratelimit:{ip}` | 1s | 网关令牌桶限流 |

**Redis 宕机降级策略**：
- 幂等 requestId：放行，由 DB 唯一键兜底
- 接口缓存：直接查 DB，性能降级但不报错
- 分布式锁：降级为 `INSERT IGNORE`，牺牲少量重复但不影响正确性
