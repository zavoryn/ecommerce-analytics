# 生产运行手册 (Runbook)

> 上线 checklist、排障 SOP、日常运维指令的速查页。
>
> 维护规则: 出现新故障类型 / 新工具时, 把"排障路径"写进本文档对应一节。

---

## 1. 上线 Checklist

### 1.1 代码与依赖

- [ ] `mvn clean verify` 全 8 模块通过
- [ ] CI (GitHub Actions) build job 绿; 镜像已推到 ghcr
- [ ] CHANGELOG / docs/07-progress.md 更新
- [ ] 涉及 SQL 变更: 已在 stage 库手动 apply, 回滚脚本备好
- [ ] 涉及配置中心: Nacos config 已发布到对应 namespace
- [ ] 涉及 Sentinel 规则: `infra/sentinel/*.json` 已发布到 Nacos `SENTINEL_GROUP`

### 1.2 灰度与回滚

- [ ] K8s deployment 用 `RollingUpdate maxUnavailable=0`
- [ ] HPA `minReplicas` ≥ 2, 避免单点
- [ ] `preStop sleep 30s` + `server.shutdown=graceful` 已生效
- [ ] 上线后 5 分钟内观察 Grafana `ecom_*` 指标无异常
- [ ] 回滚指令准备好: `kubectl rollout undo deployment/<svc> -n ecom-analytics`

### 1.3 监控告警

- [ ] Grafana `ecom-overview` 看板各 panel 有数据
- [ ] 告警通道 (`lark-default` contact point) 已发测试消息
- [ ] SkyWalking UI (http://localhost:8888) 服务图已出现新版本

---

## 2. 排障 SOP

### 2.1 现象: 埋点接口大量 5xx / 500

1. **看告警**: Grafana → Alerts → 找 `埋点上报错误率过高`, 查 firing 时间窗口
2. **看日志**: Grafana → Explore → Loki, 查询:
   ```
   {service="ecom-analytics-collector"} |= "ERROR" | json
   ```
3. **看 trace**: SkyWalking UI → Service → ecom-analytics-collector → Topology → 看下游红色边
4. **常见原因**:
   - Redis 抖 → IdempotentService 降级正常, 不应导致 5xx; 检查 Redis 健康度
   - RocketMQ broker 故障 → LocalBufferFallback 应兜底, 看 fallback 命中率
   - DB 写满 (id_mapping) → 检查磁盘 + 慢 SQL
5. **临时止血**: Sentinel 流控规则调小, 降低写入压力, 观察恢复

### 2.2 现象: 查询接口慢 (P95 > 500ms)

1. **看缓存命中率**: Grafana → 缓存命中率 panel, < 80% 说明 cache 击穿
2. **看 SQL**: SkyWalking → Trace → 找慢 trace, 看 JDBC span 哪个 SQL 慢
3. **看 ClickHouse**: 大查询走 CK, 检查 `system.query_log` 最近慢查询:
   ```sql
   SELECT query, query_duration_ms, read_rows
   FROM system.query_log
   WHERE event_time > now() - 600
     AND query_duration_ms > 1000
   ORDER BY query_duration_ms DESC LIMIT 20;
   ```
4. **临时止血**: 降级到聚合表查询 / Nacos 推送降级开关 / 加大 Redis L2 TTL

### 2.3 现象: MQ 消费延迟堆积

1. **看 Grafana**: MQ 消费延迟 panel, P95 > 5s 触发告警
2. **看 RocketMQ console** (8856): 看消费组 LAG; LAG 持续上涨说明消费慢于生产
3. **trace 查根因**: SkyWalking → trace 某条消息, 看 EventPersistService.persist 耗时分布
4. **常见原因**:
   - DB 写慢 (锁等待 / 索引失效)
   - 单实例处理能力不够 → HPA 拉高 processor 副本
   - 死信循环 → 看 `%DLQ%GROUP_*` 是否有大量积压
5. **临时止血**:
   - K8s `kubectl scale deployment/processor --replicas=10`
   - 必要时手工把 DLQ 消息 dump 到文件, 让消费组先吞掉积压

### 2.4 现象: 死信告警 (飞书收到 [ERROR] MQ 死信)

1. **看告警卡片**: 飞书消息含 msgId / topic / 消息体
2. **拿到消息体后**: 在测试库手动 replay 看必现原因
3. **修复方案**:
   - 代码 bug → 上线修复 + 把 DLQ 消息 resend 回原 topic
   - 数据脏 → 在测试环境清洗后用 OrderTxProducer 重发
   - 业务变更导致 → 评估是否丢弃
4. **死信清空**: 修复后 `mqadmin updateConsumeOffset` 把 DLQ offset 推到最新

### 2.5 现象: 聚合任务失败 (飞书收到 [CRITICAL] 聚合任务失败)

1. **看任务名**: 飞书告警标题指出 Step1~Step5 哪步失败
2. **看异常**: 卡片里有异常类 + 异常信息
3. **手工补跑**: 登录 processor 容器, 用 Spring Actuator 触发 (生产环境可加 `/actuator/scheduledTasks/run/dailyAggregate`)
4. **常见原因**:
   - 上游 event_detail 没数据 (Canal 同步停了)
   - MySQL 主从延迟太大, slave 还没复制完
   - ClickHouse 短暂不可用
5. **数据补偿**: 等异常恢复后, 手工跑昨日 + 今日两遍, 用 ON DUPLICATE KEY 自动幂等

### 2.6 现象: JVM 堆告警 (firing critical)

1. **看是哪个服务**: 告警 service label
2. **看堆栈**: Grafana Loki → 该服务 ERROR 日志, 找 OOM 或大对象提示
3. **dump heap**:
   ```bash
   kubectl exec -n ecom-analytics deploy/<svc> -- jmap -dump:format=b,file=/tmp/heap.bin 1
   kubectl cp ecom-analytics/<pod>:/tmp/heap.bin ./heap.bin
   ```
4. **临时止血**: 重启该 pod (HPA 会顶上); 长期看 -Xmx 是否需要调大或排查泄漏

---

## 3. 常用查询

### 3.1 Prometheus 表达式

```promql
# 当前每个服务的 QPS
sum by (service) (rate(http_server_requests_seconds_count[1m]))

# 当前 5xx 错误率
sum(rate(http_server_requests_seconds_count{status=~"5.."}[5m]))
  / sum(rate(http_server_requests_seconds_count[5m]))

# 单服务 P95 延迟
histogram_quantile(0.95,
  sum by (le, service, uri) (rate(http_server_requests_seconds_bucket[5m])))
```

### 3.2 Loki LogQL

```logql
# 某 traceId 全链路
{job="ecom-analytics"} | json | traceId = "abc123"

# 最近 10 分钟 ERROR 按服务计数
sum by (service) (count_over_time({job="ecom-analytics"} |= "ERROR" [10m]))
```

### 3.3 MySQL 慢查询

```sql
-- 当前正在执行的长查询
SELECT id, time, state, info FROM information_schema.processlist
WHERE command != 'Sleep' AND time > 5 ORDER BY time DESC;

-- 最近聚合任务执行情况(用 platform_daily 反推)
SELECT event_date, updated_at FROM platform_daily
ORDER BY event_date DESC LIMIT 7;
```

### 3.4 ClickHouse

```sql
-- 今日埋点量
SELECT count() FROM events_local WHERE event_date = today();

-- 漏斗(任意 2h 窗口)
SELECT level, count() AS users FROM (
  SELECT device_id,
    windowFunnel(7200)(event_time,
      event_name='search', event_name='view_item',
      event_name='add_cart', event_name='pay_order') AS level
  FROM events_local WHERE event_date = today()
  GROUP BY device_id
) GROUP BY level ORDER BY level;
```

---

## 4. 常用命令

### 4.1 docker compose

```bash
cd infra
docker compose up -d                    # 起核心 (mysql/redis/rmq/kafka/nacos/ck/es)
docker compose --profile monitor up -d  # +监控栈 (prom/grafana/loki/promtail)
docker compose --profile apm up -d      # +SkyWalking
docker compose --profile tools up -d    # +可选 (kibana/sentinel-dashboard)

docker compose logs -f --tail=200 <svc>
docker compose restart <svc>
docker compose down                     # 不删卷
docker compose down -v                  # 删卷(慎用)
```

### 4.2 kubectl

```bash
# 状态
kubectl -n ecom-analytics get all
kubectl -n ecom-analytics describe pod <pod>

# 滚动
kubectl -n ecom-analytics rollout status deployment/<svc>
kubectl -n ecom-analytics rollout undo   deployment/<svc>

# 扩缩
kubectl -n ecom-analytics scale deployment/<svc> --replicas=N

# 调试
kubectl -n ecom-analytics logs -f deploy/<svc>
kubectl -n ecom-analytics exec -it deploy/<svc> -- sh
kubectl -n ecom-analytics port-forward svc/grafana 3000:3000
```

### 4.3 maven

```bash
mvn clean package -DskipTests           # 全打包
mvn -pl ecom-analytics-query -am compile -DskipTests
mvn -pl ecom-analytics-collector test -Dtest=IdempotentServiceIT   # 集成测试
```

### 4.4 Sentinel 规则发布

```bash
# 推送 gateway-flow-rules.json 到 Nacos SENTINEL_GROUP
curl -X POST 'http://nacos:8848/nacos/v1/cs/configs' \
  -d 'dataId=gateway-flow-rules.json' \
  -d 'group=SENTINEL_GROUP' \
  -d 'type=json' \
  --data-urlencode "content=$(cat infra/sentinel/gateway-flow-rules.json)"
```

### 4.5 JWT 调试

```bash
# 生成本地 dev token (k6 / curl 调试用)
cd infra/k6
./gen-jwt.sh                              # 默认 uid=1
USER_ID=42 TTL_SECONDS=600 ./gen-jwt.sh   # 自定义

# 测试受保护接口
TOKEN=$(./gen-jwt.sh)
curl -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/query/item-trend?itemId=888
```

---

## 5. 应急联系

| 角色 | 联系 |
|---|---|
| 在线值班 | 飞书机器人告警群 (LarkAlertService / Grafana → lark-default 都进此群) |
| 上线协同 | Nacos / Sentinel Dashboard / Grafana 三件套 |
| 数据修复 | 先 stage 库验证, 再灰度 1% 流量, 再全量 |

---

## 6. SLO 与黄金指标

| 指标 | 目标 | 当前告警阈值 |
|---|---|---|
| 埋点 API 可用性 | ≥ 99.9% | 错误率 > 5% 持续 5min |
| 查询 P95 | < 200ms | P95 > 1000ms 持续 5min |
| MQ 消费 P95 延迟 | < 2s | > 5s 持续 5min |
| 缓存命中率 | ≥ 90% | < 80% 持续 10min |
| JVM 堆 | < 80% 长期 | > 90% 持续 5min |
| 死信入库 | = 0 | > 0 立刻报 |
