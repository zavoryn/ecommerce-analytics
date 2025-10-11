# k6 性能基线

> 本文档与 `*.js` 中的 `thresholds` 同源, 任何调整必须双向同步。

## 基线总览

| 接口 | 负载模型 | P95 延迟 | P99 延迟 | 错误率 | 说明 |
|---|---|---|---|---|---|
| `POST /api/collect/event` | 0→100 并发 ramp, 持续 60s | < 200ms | < 500ms | < 1% | 埋点上报, 主要瓶颈在 Redis SETNX + MQ 异步发送 |
| `GET /api/query/item-trend` | 50 并发恒定, 60s | < 100ms | < 300ms | < 1% | 走 event_agg_daily 聚合表 + Redis 缓存 |
| `GET /api/ranking/top-items` | 0→200 并发 ramp, 60s | < 50ms  | < 150ms | < 1% | 多级缓存(Caffeine L1 + Redis L2), 高命中率 |

## 基线设定原则

1. **业务侧 SLO 倒推**: 大盘秒级刷新可接受, 取 P95 100ms / P99 300ms 作为查询接口红线
2. **缓存层级体现**: 排行榜走 L1, 应明显快于趋势查询(L2 + 聚合表)
3. **错误率统一 < 1%**: 包含网络抖动 + 业务校验失败, 不严格区分

## 调整流程

- 若新业务上线导致延迟上涨, 先在 PR 中分析根因(慢 SQL? 锁竞争?), 修复后再调基线
- 调高阈值视为放水, 需在 PR 描述中给出业务理由
- 基线下调(SLO 收紧)需通过性能压测 3 次稳定证明可达成

## CI 集成

`.github/workflows/perf.yml`:
- 触发: 手动 (workflow_dispatch) 或 push 含 `[perf]` 关键字的 commit
- 启动 docker-compose 起完整环境
- 跑 `run-all.sh`, 阈值不达自动 FAIL

## 本地运行

```bash
# 1. 启动完整环境
cd ../  # to infra/
docker compose --profile monitor up -d
cd ..
mvn -DskipTests package
# 启动 5 个 jar (或脚本)

# 2. 准备 JWT (查询/排行榜需要)
TOKEN=$(./gen-jwt.sh)   # 或手工调 /api/collect/login 拿一个

# 3. 跑压测
cd infra/k6
TOKEN="$TOKEN" ./run-all.sh
```

## 注意事项

- k6 不带任何重试逻辑, 应用层网络抖动会直接计入错误率
- 测试要打在 gateway 上(不绕过限流), 真实流量都过 gateway
- 测试 traceId 透传: 脚本里设置了 `X-Trace-Id: perf-<vu>-<iter>`, 可在 logs/ 里抽样查链路
