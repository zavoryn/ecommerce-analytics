# 系统架构设计

## 1. 项目定位

本系统是电商平台的**数据分析后端**，职责是：

1. **采集**用户行为（浏览/点击/加购/支付）和销售数据
2. **清洗**：过滤作弊流量、标准化格式、关联行为与订单
3. **存储**：多源异构存储，各司其职
4. **提供接口**：给运营决策、选品提供数据支持

---

## 2. 整体架构图

```
┌─────────────────────────────────────────────────────────────────┐
│                         客户端 / 前端                            │
│            埋点 SDK（自动上报 device_id + session_id）           │
└────────────────────────┬────────────────────────────────────────┘
                         │ HTTPS POST /api/collect/event
                         ▼
┌─────────────────────────────────────────────────────────────────┐
│              ecom-analytics-gateway（Spring Cloud Gateway）      │
│         Nacos 服务注册 │ Sentinel 限流 │ 全局接入日志              │
└────┬───────────────────┬──────────────────────┬─────────────────┘
     │                   │                      │
     ▼                   ▼                      ▼
┌──────────┐      ┌────────────┐        ┌────────────┐
│collector │      │  query     │        │  search    │
│ 采集服务  │      │ 查询服务   │        │ 搜索服务   │
│ :8081    │      │ :8083      │        │ :8084      │
└────┬─────┘      └──────┬─────┘        └──────┬─────┘
     │                   │                     │
     │ 写日志兜底         │ 聚合表优先           │ ES 检索
     │                   │ 实时走明细           │ search_after
     ▼                   │                     │ 深分页
┌────────────────────────────────────────────────────────┐
│                    消息中间件层                          │
│                                                        │
│  Kafka（埋点高吞吐）  RocketMQ（订单可靠投递）            │
│  topic: ecom-user-events    topic: TOPIC_ORDER_SYNC    │
└───────────────────┬────────────────────────────────────┘
                    │
                    ▼
┌─────────────────────────────────────────────────────────┐
│        ecom-analytics-processor（数据处理服务）           │
│        ecom-analytics-bigdata（Flink 清洗作业）          │
│                                                         │
│  ① Flink 消费 Kafka → 清洗 → 反作弊 → 时间戳校验         │
│  ② 双流 Join: 行为流 JOIN 订单流 → DWD 宽表              │
│  ③ Processor 消费 RocketMQ → 落 MySQL（订单）            │
└───┬───────────────────────┬──────────────────┬──────────┘
    │                       │                  │
    ▼                       ▼                  ▼
┌──────────┐        ┌───────────────┐   ┌────────────┐
│  MySQL   │        │  ClickHouse   │   │    ES      │
│ 核心交易  │        │ 埋点OLAP分析  │   │ 商品/订单   │
│          │        │               │   │ 全文检索    │
│ order_   │        │ events_local  │   │            │
│ sync     │        │ dwd_user_     │   │ product_   │
│ event_   │        │ order_action  │   │ index      │
│ agg_     │        │ dws_item_     │   │ order_     │
│ daily    │        │ daily         │   │ event_index│
└──────────┘        └───────────────┘   └────────────┘
    ▲                                        ▲
    │ Canal binlog 监听                       │
    └────────────────────────────────────────┘
       (MySQL → ES 准实时同步)
```

---

## 3. 微服务模块说明

| 服务 | 端口 | 核心职责 |
|---|---|---|
| `ecom-analytics-gateway` | 8080 | 统一入口，限流/路由/日志 |
| `ecom-analytics-collector` | 8081 | 接收埋点、发 Kafka/MQ、本地日志兜底 |
| `ecom-analytics-processor` | 8082 | 消费 MQ、落 MySQL、定时聚合/校验/补偿 |
| `ecom-analytics-query` | 8083 | 趋势/漏斗/TOP10 查询，Redis 缓存 |
| `ecom-analytics-search` | 8084 | ES 商品/订单检索，search_after 深分页 |
| `ecom-analytics-bigdata` | — | Flink 作业（独立部署到 Flink 集群） |
| `ecom-analytics-common` | — | 公共 DTO/工具/常量 |

---

## 4. 存储职责划分（核心设计决策）

> **这是最重要的一张表，面试时要能脱口而出**

| 数据类型 | 存储 | 原因 |
|---|---|---|
| 订单/支付核心交易 | **MySQL** | 需要 ACID 事务、强一致 |
| 用户行为埋点明细 | **ClickHouse** | 列存 OLAP，PB 级毫秒查询 |
| 行为-订单关联宽表 | **ClickHouse** | Flink 双流 Join 后落 CK |
| 每日预聚合指标 | **MySQL + CK 双写** | MySQL 供小型查询，CK 供大数据量 |
| 商品/订单检索索引 | **Elasticsearch** | IK 分词全文检索，search_after 深分页 |
| 热点查询缓存 | **Redis** | 10min TTL，挡 100 并发 |
| 历史全量快照 | **Hive**（扩展方向） | T+1 离线批分析，SQL 任意维度 |

### 为什么埋点数据不存 MySQL？

MySQL 是**行式存储（OLTP）**，读数据时即使只需要 1 列，也要把整行从磁盘读出。  
对"统计 5000 万条中某个字段的 SUM"这类操作，I/O 是巨大浪费。

ClickHouse 是**列式存储（OLAP）**：
- 只读需要的列，其余列不碰磁盘
- 同一列数据物理相邻，压缩率极高（LZ4/ZSTD）
- SIMD 向量化计算：一条 AVX-512 指令处理 512 bit 数据
- 结果：PB 级数据的 SUM/COUNT/GROUP BY 毫秒级出结果

---

## 5. 关键技术选型说明

### RocketMQ vs Kafka

| 对比项 | RocketMQ | Kafka |
|---|---|---|
| 设计定位 | 业务消息（事务/延迟/死信） | 日志流（高吞吐，削峰） |
| 吞吐量 | 十万级 QPS | 百万级 QPS |
| 事务消息 | ✅ 支持 | ❌ 不支持 |
| 死信队列 | ✅ 内置 | ❌ 需自行实现 |
| 本项目用途 | 订单同步（可靠性第一） | 埋点数据（吞吐量第一） |

### ClickHouse MergeTree 核心原理

```
写入时：
  数据 → 追加写磁盘小 Part（顺序 IO，极快）
         后台 Merge 线程合并成大 Part

读取时：
  稀疏索引（每 8192 行一个 Granule）
  → 定位目标 Granule
  → 仅加载该列数据到内存
  → SIMD 向量化计算
```

### ES 倒排索引 vs MySQL LIKE

```
MySQL LIKE '%连衣裙%'：
  B+ 树索引失效 → 全表扫描 → 百万行 → 卡死

ES "连衣裙"：
  IK 分词 → 查 FST 词典（内存，O(len)）
  → 定位倒排表 Block 偏移 → FOR 解压
  → Roaring Bitmap 位运算求交集
  → 千万 SKU 毫秒级出结果
```

---

## 6. 数据一致性保证

### 行为数据：最终一致

```
前端重试3次 → 本地缓存(localStorage) → 网络恢复重发
       ↓
后端接收：写本地滚动日志（100MB切割，24h保留）
       ↓
Kafka 发送失败 → LocalBufferFallback（内存队列 1万条上限）
       ↓
定时任务：每10min 扫日志 vs DB，缺失的补写
       ↓
凌晨全量校验：生成 data_verify_report 对账报告
```

### 订单数据：版本号乐观锁

```sql
-- 取消订单后旧消息不会覆盖新状态
ON DUPLICATE KEY UPDATE
  order_status = IF(VALUES(version) > version, VALUES(order_status), order_status),
  version      = GREATEST(version, VALUES(version))
```

### 幂等：三层防护

1. Redis SETNX requestId（5min TTL）—— 高频拦截
2. DB 唯一键 uk_idempotent —— 兜底
3. Redis 宕机时自动降级到 DB 唯一键

---

## 7. 性能指标（目标）

| 接口 | 响应时间 | 备注 |
|---|---|---|
| 埋点上报 | < 50ms | 异步写 Kafka，不等 DB |
| 商品近7天趋势 | < 100ms | 聚合表 + Redis 缓存 |
| 漏斗分析 | < 500ms | ClickHouse windowFunnel |
| 商品搜索 | < 200ms | ES 倒排索引 |
| 订单明细翻页 | < 300ms | ES search_after |
