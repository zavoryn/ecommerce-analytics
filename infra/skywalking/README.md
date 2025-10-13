# SkyWalking APM 接入

## 接入设计

```
┌────────────────────┐  HTTP/RocketMQ/JDBC ┌────────────────────┐
│  5 个微服务         │ ─────────────────→ │  自动埋点(无侵入)   │
│  + javaagent       │                     │                    │
└──────────┬─────────┘                     └────────────────────┘
           │ gRPC 11800
           ▼
   ┌───────────────┐                ┌──────────────┐
   │ SkyWalking    │                │ SkyWalking   │
   │ OAP Server    │ ──── 12800 ──→ │ UI (8888)    │
   │ (h2 / es)     │                │              │
   └───────────────┘                └──────────────┘
```

- **agent**: 注入到每个微服务 Dockerfile, Java 启动时 `-javaagent:/opt/skywalking-agent/skywalking-agent.jar`
- **OAP**: 接收 agent 上报, 聚合存储到 H2 (demo) / ES (生产)
- **UI**: 浏览器查看拓扑图 / trace 详情 / SLA 报表

## 自动覆盖的组件

SkyWalking Java agent 9.2.0 自动埋点以下组件, **应用代码 0 改动**:

| 类型 | 组件 |
|---|---|
| Web | Spring MVC / WebFlux / Spring Cloud Gateway |
| RPC | OpenFeign / Dubbo / gRPC |
| DB  | MySQL / PostgreSQL / ClickHouse JDBC |
| Cache | Lettuce (Redis) / Jedis |
| MQ  | RocketMQ / Kafka / RabbitMQ |
| 异步 | CompletableFuture / Reactor |

应用本身不需要引入任何 SDK 或注解。

## 启动

```bash
# 1. 启动 SkyWalking OAP + UI
cd infra
docker compose --profile apm up -d skywalking-oap skywalking-ui

# 2. 启动应用 (镜像里已含 agent)
docker compose --profile apm up -d   # 或者直接跑 jar

# 3. 浏览器打开 UI
open http://localhost:8888
```

## 与现有 TraceId 的关系

我们项目里**已经有一套手写的 `TraceIdHolder` / `TraceIdGatewayFilter` / `RocketMqTrace`**, 它做的是:

- 接受外部传入的 `X-Trace-Id` Header
- 注入 MDC, 让 logback `%X{traceId}` 输出到 JSON 日志
- 跨 RocketMQ 透传

SkyWalking 做的是更上层的 APM:

- 自动捕获方法级别 span (耗时分布 / 调用关系)
- 服务拓扑图 / 链路追踪 UI
- 慢请求 / 错误分析

**两者互补**, 不冲突, 不去重:

| 能力 | TraceIdHolder | SkyWalking |
|---|---|---|
| 日志关联 (logback %X{traceId}) | ✅ | ❌ (用自己的 traceId) |
| 跨服务追踪 | ✅ 手写 RocketMQ 透传 | ✅ 自动 |
| Trace UI / 拓扑图 | ❌ | ✅ |
| 性能开销 | 几乎 0 | ~3-5% |

如需把 SkyWalking traceId 也输出到 MDC, 引入 `apm-toolkit-logback-1.x`:

```xml
<!-- 在 common pom 里加 (可选) -->
<dependency>
  <groupId>org.apache.skywalking</groupId>
  <artifactId>apm-toolkit-logback-1.x</artifactId>
  <version>9.2.0</version>
</dependency>
```

然后 logback pattern 里加 `%tid` (SkyWalking 提供的 Converter)。

## 关闭 APM

环境变量覆盖:

```bash
docker run -e JAVA_OPTS="-Xms512m -Xmx1g" ...   # 不带 -javaagent
```

或在 K8s deployment 里改 env JAVA_OPTS。

## 生产建议

| 项 | 建议 |
|---|---|
| 存储后端 | H2 → Elasticsearch (生产 OAP 启动指定 `SW_STORAGE=elasticsearch`) |
| OAP 高可用 | 多实例 + Nacos/ZK 注册, agent 配置多个 backend services |
| 采样率 | 全采样 → 按业务量配置采样, 见 agent.config `agent.sample_n_per_3_secs` |
| 日志关联 | 引入 apm-toolkit-logback-1.x, traceId 双向打通 |
| 慢请求阈值 | OAP 默认 1000ms, 业务侧可降到 500ms |

## 排查

| 现象 | 排查 |
|---|---|
| UI 看不到服务 | 检查 agent 启动日志 `/opt/skywalking-agent/logs/skywalking-api.log`; 确认 OAP gRPC 端口 11800 可达 |
| 拓扑图缺线 | 跨服务调用的下游进程是否也带 agent? |
| Trace 没数据 | 采样率 / 时间筛选 / 业务量是否够 |
| OAP 启动失败 | 调大 JAVA_OPTS heap; H2 模式下数据不持久, 重启即丢 |
