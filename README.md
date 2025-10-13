# ecom-analytics · 电商数据分析系统

> 电商平台数据分析后端，负责用户行为采集、大数据清洗存储、多维聚合查询全链路。  
> 技术栈：Spring Cloud · MySQL · ClickHouse · Elasticsearch · Kafka · RocketMQ · Flink · Redis

[![Java](https://img.shields.io/badge/Java-17-orange)](https://openjdk.org/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.2-brightgreen)](https://spring.io/projects/spring-boot)
[![ClickHouse](https://img.shields.io/badge/ClickHouse-24.x-yellow)](https://clickhouse.com/)
[![License](https://img.shields.io/badge/License-MIT-blue)](LICENSE)

---

## 系统架构

```
前端埋点 SDK
    │  POST /api/collect/event（device_id + session_id + event_name + properties）
    ▼
┌─────────────────── Gateway :8080 ─────────────────────┐
│          Nacos 服务发现 · Sentinel 限流 · 接入日志        │
└──────────┬────────────────┬──────────────┬────────────┘
           │                │              │
     collector          query          search
      :8081             :8083           :8084
           │
    ┌──────┴──────────────┐
    │   Kafka（埋点削峰）   │   RocketMQ（订单可靠投递）
    └──────┬──────────────┘
           │ Flink 实时清洗 + 双流 Join
           ▼
  ┌─────────────────────────────────────────────┐
  │  ClickHouse（埋点 OLAP，埋点数据的最终存储）  │
  │  · events_local（明细，windowFunnel 漏斗）   │
  │  · dwd_user_order_action（行为-订单宽表）    │
  │  · dws_item_daily（每日汇总）                │
  └─────────────────────────────────────────────┘
  ┌──────────────────┐  ┌──────────────────────┐
  │  MySQL（交易数据） │  │  ES（商品/订单检索）  │
  │  · order_sync     │  │  · product_index     │
  │  · event_agg_daily│  │  · order_event_index │
  └──────────────────┘  └──────────────────────┘
```

> **存储职责分工**（最重要的一张表）
>
> | 数据 | 存储 | 原因 |
> |---|---|---|
> | 订单/支付 | MySQL | ACID 事务，强一致 |
> | 埋点行为明细 | ClickHouse | 列存 OLAP，PB 级毫秒查询 |
> | 行为-订单宽表 | ClickHouse | Flink 双流 Join 后落 CK |
> | 商品/订单检索 | ES | 倒排索引，search_after 深分页 |
> | 热点缓存 | Redis | 10min TTL，挡高并发 |

---

## 模块说明

| 模块 | 端口 | 职责 |
|---|---|---|
| `ecom-analytics-gateway` | 8080 | 统一入口：限流/路由/日志 |
| `ecom-analytics-collector` | 8081 | 接收埋点，写日志兜底，发 Kafka/MQ |
| `ecom-analytics-processor` | 8082 | 消费 MQ，落库，定时聚合/校验/补偿 |
| `ecom-analytics-query` | 8083 | 趋势/漏斗/TOP10 查询，Redis 缓存 |
| `ecom-analytics-search` | 8084 | ES 商品/订单检索，search_after 深分页 |
| `ecom-analytics-bigdata` | — | Flink 清洗作业骨架（独立部署） |
| `ecom-analytics-common` | — | 公共 DTO/枚举/工具 |

---

## 快速启动

### 1. 启动基础设施

```bash
cd infra && docker compose up -d
```

等待约 60s，访问 Nacos：http://localhost:8848/nacos （admin/nacos）

### 2. 初始化 ES 索引

```bash
# 安装 IK 分词插件
docker exec ecom-es ./bin/elasticsearch-plugin install analysis-ik
docker restart ecom-es

# 创建索引
curl -X PUT localhost:9200/product_index \
     -H "Content-Type: application/json" \
     -d @infra/elasticsearch/product_mapping.json

curl -X PUT localhost:9200/order_event_index \
     -H "Content-Type: application/json" \
     -d @infra/elasticsearch/order_event_mapping.json
```

### 3. 编译启动

```bash
mvn clean package -DskipTests

# 各模块按顺序启动（或用 IDEA 同时启动）
java -jar ecom-analytics-gateway/target/*.jar
java -jar ecom-analytics-collector/target/*.jar
java -jar ecom-analytics-processor/target/*.jar
java -jar ecom-analytics-query/target/*.jar
java -jar ecom-analytics-search/target/*.jar
```

### 4. 验证

```bash
# 上报埋点
curl -X POST localhost:8080/api/collect/event \
  -H "Content-Type: application/json" \
  -d '{"requestId":"t001","deviceId":"dev-1","sessionId":"s-1",
       "eventName":"view_item","timestamp":1705296000000,
       "properties":{"item_id":888,"price":299}}'

# 查趋势
curl "localhost:8080/api/query/item-trend?itemId=888&days=7"

# 搜索
curl "localhost:8080/api/search/product?keyword=连衣裙"
```

---

## 核心技术点

### ClickHouse windowFunnel 漏斗分析

```sql
-- 2小时窗口内，search → view → add_cart → pay 四步转化率
SELECT level, count() AS user_cnt FROM (
    SELECT device_id,
        windowFunnel(7200)(event_time,
            event_name='search', event_name='view_item',
            event_name='add_cart', event_name='pay_order') AS level
    FROM events_local
    WHERE event_date BETWEEN '2025-01-08' AND '2025-01-15'
    GROUP BY device_id
) GROUP BY level ORDER BY level DESC;
```

### ES search_after 深分页

```java
// 不管翻到第几页，性能恒定（vs LIMIT OFFSET 越翻越慢）
SearchRequest req = new SearchRequest.Builder()
    .index("product_index")
    .query(boolQuery)
    .sort(s -> s.field(f -> f.field("sales30d").order(SortOrder.Desc)))
    .searchAfter(List.of(prevPageLastSales30d, prevPageLastItemId))
    .size(20)
    .build();
```

### 幂等三层防护

```
Layer 1: Redis SETNX requestId (5min TTL) ──── 高频拦截，毫秒级
Layer 2: DB UNIQUE KEY uk_idempotent ─────────── 兜底，Redis 宕机时生效
Layer 3: REPLACE INTO (delete+insert 语义) ───── 终极兜底
```

### 分月分表 + 跨表路由

```java
// 按时间范围自动计算需要查询的子表列表
List<String> tables = MonthlyTableUtil.tablesBetween(from, to);
// → ["event_detail_202501", "event_detail_202502"]
// 跨月查询 → 走 ES search_after 避免内存合并 OOM
```

---

## 文档索引

| 文档 | 内容 |
|---|---|
| [docs/01-architecture.md](docs/01-architecture.md) | 系统架构 + 存储选型说明 |
| [docs/02-data-warehouse.md](docs/02-data-warehouse.md) | 数仓四层分层 + OneID 方案 |
| [docs/03-database-design.md](docs/03-database-design.md) | MySQL/CK/ES/Redis 表结构设计 |
| [docs/04-api-guide.md](docs/04-api-guide.md) | 接口文档 + 请求响应示例 |
| [docs/05-deployment.md](docs/05-deployment.md) | 部署步骤 + 环境变量 |
| [docs/06-interview-notes.md](docs/06-interview-notes.md) | 面试要点 ↔ 代码位置对照 |
| [CONTRIBUTING.md](CONTRIBUTING.md) | 贡献指南 |

---

## 项目背景

本项目源于真实实习经历，作为电商项目数据分析系统的后端核心模块：
- 负责用户行为数据（点击/浏览/加购/支付）和销售数据的全链路采集
- 解决了查询慢（引入聚合表+缓存+分表）、数据不一致（幂等+补偿+版本号）等生产问题
- 在面试中深入探讨了大厂级别的架构演进方向（Kafka+Flink+ClickHouse+ES）

## License

MIT © 2025
