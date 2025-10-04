# 部署与启动指南

## 1. 本地开发（推荐）

### 环境要求

| 工具 | 版本 | 说明 |
|---|---|---|
| JDK | 17+ | Spring Boot 3.x 最低要求 |
| Maven | 3.8+ | |
| Docker Desktop | 24+ | 启动所有中间件 |

### 步骤一：启动基础设施

```bash
cd infra
# 启动所有中间件（MySQL/Redis/RocketMQ/Kafka/Nacos/ClickHouse/ES）
docker compose up -d

# 可选：同时启动 Kibana + Sentinel Dashboard（调试用）
docker compose --profile tools up -d

# 检查各组件健康
docker compose ps
```

等待约 60 秒，检查以下端口可访问：
- Nacos 控制台：http://localhost:8848/nacos （admin/nacos）
- RocketMQ 控制台：http://localhost:8080 （如有单独部署）
- ClickHouse HTTP：http://localhost:8123/ping → 返回 `Ok.`
- ES：http://localhost:9200 → 返回集群信息

### 步骤二：初始化数据库

MySQL 和 ClickHouse 的初始化脚本已通过 `docker-entrypoint-initdb.d` 自动执行。  
如需手动执行：
```bash
# MySQL
docker exec -i ecom-mysql mysql -uroot -proot ecom_analytics < infra/mysql/init.sql

# ClickHouse
docker exec -i ecom-clickhouse clickhouse-client --database=ecom_analytics \
    < infra/clickhouse/init.sql
```

### 步骤三：初始化 ES 索引

```bash
# 创建商品索引（带 IK 分词配置）
curl -X PUT "localhost:9200/product_index" \
     -H "Content-Type: application/json" \
     -d @infra/elasticsearch/product_mapping.json

# 创建订单事件索引
curl -X PUT "localhost:9200/order_event_index" \
     -H "Content-Type: application/json" \
     -d @infra/elasticsearch/order_event_mapping.json
```

> **注意**：IK 分词插件需要安装才能使用，  
> 执行：`docker exec ecom-es ./bin/elasticsearch-plugin install analysis-ik`  
> 然后重启 ES：`docker restart ecom-es`

### 步骤四：编译项目

```bash
# 回到项目根目录
cd ..

# 编译（跳过测试，首次编译）
mvn clean compile -DskipTests

# 完整构建
mvn clean package -DskipTests
```

### 步骤五：按顺序启动服务

```bash
# 1. 先启动 Gateway
cd ecom-analytics-gateway
mvn spring-boot:run

# 2. 采集服务
cd ../ecom-analytics-collector
mvn spring-boot:run

# 3. 处理服务
cd ../ecom-analytics-processor
mvn spring-boot:run

# 4. 查询服务
cd ../ecom-analytics-query
mvn spring-boot:run

# 5. 搜索服务
cd ../ecom-analytics-search
mvn spring-boot:run
```

或者使用 IntelliJ IDEA 同时启动多个 Spring Boot 应用。

### 步骤六：验证

```bash
# 上报一条埋点
curl -X POST "localhost:8080/api/collect/event" \
     -H "Content-Type: application/json" \
     -d '{
       "requestId":"test-001",
       "deviceId":"device-abc",
       "sessionId":"sess-xyz",
       "eventName":"view_item",
       "timestamp":1705296000000,
       "properties":{"item_id":888,"category":"女装","price":299.0}
     }'

# 查询商品趋势
curl "localhost:8080/api/query/item-trend?itemId=888&days=7"

# 搜索商品
curl "localhost:8080/api/search/product?keyword=连衣裙&pageSize=10"
```

---

## 2. 环境变量配置

各服务支持通过环境变量覆盖默认配置：

| 变量 | 默认值 | 说明 |
|---|---|---|
| `NACOS_ADDR` | 127.0.0.1:8848 | Nacos 地址 |
| `MYSQL_HOST` | 127.0.0.1 | MySQL 主机 |
| `MYSQL_PORT` | 3306 | |
| `MYSQL_USER` | root | |
| `MYSQL_PASSWORD` | root | |
| `CK_HOST` | 127.0.0.1 | ClickHouse 主机 |
| `CK_PORT` | 8123 | HTTP 接口端口 |
| `REDIS_HOST` | 127.0.0.1 | |
| `REDIS_PORT` | 6379 | |
| `ROCKETMQ_NAMESRV` | 127.0.0.1:9876 | |
| `ES_HOST` | 127.0.0.1 | |
| `ES_PORT` | 9200 | |

---

## 3. Flink 大数据作业部署（可选）

Flink 作业独立部署到 Flink 集群，与 Spring 微服务解耦：

```bash
# 打包 bigdata 模块
mvn package -pl ecom-analytics-bigdata -DskipTests

# 提交到 Flink 集群
flink run \
  -c com.ecom.analytics.bigdata.flink.EventCleanJob \
  ecom-analytics-bigdata/target/ecom-analytics-bigdata-1.0.0-SNAPSHOT.jar \
  --kafka.bootstrap-servers localhost:9092 \
  --clickhouse.url jdbc:clickhouse://localhost:8123/ecom_analytics
```

---

## 4. 服务端口汇总

| 服务 | 端口 | Swagger |
|---|---|---|
| Gateway | 8080 | — |
| Collector | 8081 | http://localhost:8081/doc.html |
| Processor | 8082 | — |
| Query | 8083 | http://localhost:8083/doc.html |
| Search | 8084 | http://localhost:8084/doc.html |
| Nacos | 8848 | http://localhost:8848/nacos |
| MySQL | 3306 | — |
| Redis | 6379 | — |
| RocketMQ | 9876 | — |
| Kafka | 9092 | — |
| ClickHouse | 8123 | http://localhost:8123/play |
| Elasticsearch | 9200 | http://localhost:9200 |
| Kibana | 5601 | http://localhost:5601 |
