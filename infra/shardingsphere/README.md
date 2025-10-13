# ShardingSphere-JDBC 接入

## 接入范围

| 模块 | 改动 | 效果 |
|---|---|---|
| processor | 写主读从 + `event_detail` 按月分表 | 写库压力降低, 业务侧 SQL 透明 |
| query | 仅写主读从 (运营查询基本走 slave) | 主库 IO 让给写, 查询走从 |

## 设计要点

### 1. 接入方式: JDBC URL 协议

ShardingSphere-JDBC 5.4.x 支持作为标准 JDBC Driver 接入:

```
url: jdbc:shardingsphere:classpath:sharding-config.yaml
```

应用层代码**零改动**, 既有的 `JdbcTemplate` / `MyBatis-Plus` 全部继续工作,
ShardingSphere Driver 在 SQL 执行前介入做路由。

### 2. Profile 切换

默认 profile 走单库; 加上 `sharding` profile 启用读写分离与分表:

```bash
# 单库模式 (开发 / 小流量生产)
SPRING_PROFILES_ACTIVE=prod java -jar processor.jar

# 读写分离 + 分表模式 (大流量)
SPRING_PROFILES_ACTIVE=prod,sharding \
  MYSQL_MASTER_HOST=master.db   \
  MYSQL_SLAVE_1_HOST=slave1.db  \
  MYSQL_SLAVE_2_HOST=slave2.db  \
  java -jar processor.jar
```

### 3. 分表规则: INTERVAL 算法

```yaml
shardingAlgorithms:
  event_monthly:
    type: INTERVAL
    props:
      datetime-lower: "2025-01-01 00:00:00"
      datetime-upper: "2030-12-31 23:59:59"
      sharding-suffix-pattern: "yyyyMM"
      datetime-interval-unit: "MONTHS"
      datetime-interval-amount: 1
```

`INTERVAL` 算法的优势:
- 范围查询 `WHERE ts BETWEEN ? AND ?` 自动定位到涉及的几张物理表(而不是全表扫描)
- 不像 `INLINE` 算法那样需要手写表达式

### 4. 与原 `MonthlyTableUtil` 的关系

| 项 | 原方案 | 接入 ShardingSphere |
|---|---|---|
| 表名路由 | Java 代码手算 `event_detail_YYYYMM` | 由 ShardingSphere 透明路由, SQL 写 `event_detail` 即可 |
| 跨月查询 | 业务代码自己 UNION ALL | ShardingSphere 自动 UNION 物理子表结果 |
| 改造成本 | 每个 SQL 都得改 | 仅启用 profile, 代码 0 改动 |

建议: 启用 ShardingSphere 后逐步把代码里的 `MonthlyTableUtil.tableOf(...)` 调用换成
直接写 `event_detail` 逻辑表名。原工具保留兼容旧代码与降级回退。

## 验证

```bash
# 1. 启动 HA 拓扑(含 1 master + 2 slave)
cd infra
docker compose -f docker-compose-ha.yml up -d mysql-master mysql-slave-1 mysql-slave-2
# 按 README-ha.md 配置主从复制

# 2. 启用 sharding profile 启动 processor / query
mvn -DskipTests package
SPRING_PROFILES_ACTIVE=prod,sharding \
  MYSQL_MASTER_HOST=127.0.0.1 MYSQL_MASTER_PORT=3306 \
  MYSQL_SLAVE_1_HOST=127.0.0.1 MYSQL_SLAVE_1_PORT=3307 \
  MYSQL_SLAVE_2_HOST=127.0.0.1 MYSQL_SLAVE_2_PORT=3308 \
  java -jar ecom-analytics-processor/target/*.jar

# 3. 观察日志中的 ShardingSphere SQL 路由
#    打开 sharding-config.yaml 中 props.sql-show=true 可看到实际路由 SQL
```

## 排查

| 现象 | 原因 |
|---|---|
| 启动报 `Can not find rule with datasource name` | sharding-config.yaml 里 dataSources 名字与 rules 引用不一致 |
| 写入仍打到 slave | 读写分离需在事务内或显式 hint, 默认 INSERT/UPDATE/DELETE 自动走 master |
| 跨月查询慢 | 确认 INTERVAL 算法的 datetime-lower 涵盖查询时间; 时间字段为字符串时需 datetime-pattern 匹配 |
| 物理表不存在报错 | actualDataNodes 配置的子表范围必须事先建好(参考 `infra/mysql/init.sql` 初始化脚本) |

## 与本项目其他组件的对接

- **HA 拓扑** (`docker-compose-ha.yml`): 提供 master + 2 slave, 端口 3306/3307/3308
- **k6 压测** (Stage 5.1): 启用 sharding profile 后跑 query-trend.js, 应看到 slave QPS 上升
- **Grafana 看板** (Stage 4.2): JVM 指标按 service tag 分组, 启用 sharding 后查询服务 DB 等待时间应下降
