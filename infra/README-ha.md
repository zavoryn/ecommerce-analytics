# 高可用 (HA) 部署说明

## 拓扑

| 组件 | 拓扑 | 故障容忍 |
|---|---|---|
| MySQL | 1 master + 2 slave (GTID 复制) | 主挂自动切到 slave (借助 MHA/Orchestrator) |
| ClickHouse | 2 分片 × 2 副本, ZK 协调 | 任一副本挂掉, 同分片另一副本继续服务 |
| Elasticsearch | 1 master + 2 data | data 节点宕机时副本分片自动提升 |
| Redis | 1 master + 2 slave + 3 sentinel | sentinel quorum=2, master 宕 → 自动选主 |

## 启动

```bash
cd infra
docker compose -f docker-compose-ha.yml up -d
```

> ⚠️ 单机部署占用 ~12GB 内存, 仅做架构演示。生产环境请独立物理机部署。

## 启动后初始化

### MySQL 主从

启动后 master 已自动建库 + 授权账号。slave 需手动配:

```bash
# 1. 拿到 master 的 binlog 位置
docker exec ecom-mysql-master mysql -uroot -proot -e "SHOW MASTER STATUS\G"

# 2. 在每个 slave 上执行
docker exec ecom-mysql-slave-1 mysql -uroot -proot -e "
  CHANGE MASTER TO
    MASTER_HOST='ecom-mysql-master',
    MASTER_USER='repl',
    MASTER_PASSWORD='repl_pass',
    MASTER_AUTO_POSITION=1;
  START SLAVE;
  SHOW SLAVE STATUS\G"
```

### ClickHouse 集群表

在任一节点执行:

```sql
CREATE TABLE ecom_analytics.events_local ON CLUSTER ecom_cluster (...)
ENGINE = ReplicatedMergeTree('/clickhouse/tables/{shard}/events_local', '{replica}')
ORDER BY (device_id, event_date, event_time);

CREATE TABLE ecom_analytics.events_dist ON CLUSTER ecom_cluster
AS ecom_analytics.events_local
ENGINE = Distributed(ecom_cluster, ecom_analytics, events_local, rand());
```

### ES 集群

启动后自动组建。验证:

```bash
curl http://localhost:9200/_cat/nodes?v
curl http://localhost:9200/_cluster/health?pretty
```

### Redis Sentinel

应用配置:

```yaml
spring.data.redis:
  sentinel:
    master: mymaster
    nodes:
      - localhost:26379
      - localhost:26380
      - localhost:26381
```

## 应用配置切换

将 `spring.datasource.url` 切换为读写分离:

```yaml
spring:
  shardingsphere:
    rules:
      readwrite-splitting:
        data-sources:
          ds:
            type: Static
            props:
              write-data-source-name: master
              read-data-source-names: slave1,slave2
```

## 生产建议

| 项 | 建议 |
|---|---|
| MySQL HA | 用 Orchestrator + ProxySQL, 或直接上云 (RDS) |
| ClickHouse | 副本数 ≥ 2, 分片数按数据量增长水平扩展 |
| ES | 三 master + N data, master 节点不存数据 |
| Redis | Sentinel 适合中小; 超大 QPS 上 Redis Cluster |
| 备份 | xtrabackup/CK ALTER FREEZE/ES snapshot 全部接入对象存储, 每日全量 + 每小时增量 |
