# 项目开发进度

> 最后更新：2026-05-17（生产化升级 4 个 Stage + 增强 Stage 5/6 全部交付，详见 `docs/08-production-upgrade.md` 与 `docs/09-runbook.md`）
>
> **维护规则：每次代码变更后，必须同步更新本文档对应模块的进度状态，并修改顶部的「最后更新」日期。**

---

## 总体进度概览

| 模块 | 状态 | 已提交 | 未提交（本地） |
|---|---|---|---|
| common | ✅ 完成 | 6 文件 | — |
| gateway | ✅ 完成 | 3 文件 | application.yml 已修改 |
| collector | 🔧 增强中 | 8 文件 | +2 新文件，pom.yml / application.yml 已修改 |
| processor | 🔧 增强中 | 9 文件 | 5 文件已修改 |
| query | 🔧 增强中 | 8 文件 | +9 新文件，2 文件已修改 |
| search | 🔧 增强中 | 5 文件 | +2 新文件，2 文件已修改 |
| bigdata | ✅ 完成 | 3 文件 | 1 文件微调 |
| docs | 🔧 更新中 | 6 文件 | 3 文件已修改 |
| infra | 🔧 更新中 | — | mysql/init.sql / clickhouse/init.sql 已修改 |

图例：✅ 完成 → 🔧 增强中 → 🚧 开发中 → 📋 计划中

---

## 各模块详细进度

### 1. ecom-analytics-common（公共模块）

**状态：✅ 完成**

| 文件 | 说明 |
|---|---|
| `response/R.java` | 统一响应封装 |
| `enums/EventType.java` | 事件类型枚举 |
| `dto/UserEventDTO.java` | 用户行为埋点 DTO |
| `dto/OrderSyncDTO.java` | 订单同步 DTO |
| `constant/MqTopics.java` | MQ Topic 常量 |
| `util/MonthlyTableUtil.java` | 分月分表路由工具 |

### 2. ecom-analytics-gateway（网关 :8080）

**状态：✅ 完成（配置有待更新）**

| 文件 | 说明 |
|---|---|
| `filter/AccessLogFilter.java` | 全局接入日志 |
| `config/GatewayConfig.java` | 网关路由配置 |
| `GatewayApplication.java` | 启动类 |

未提交变更：
- `application.yml` — 路由规则更新

### 3. ecom-analytics-collector（采集服务 :8081）

**状态：🔧 增强中**

| 文件 | 说明 | 状态 |
|---|---|---|
| `controller/EventCollectController.java` | 埋点上报接口 | ✅ 已提交 |
| `controller/OrderSyncController.java` | 订单同步接口 | ✅ 已提交 |
| `controller/LoginController.java` | 登录接口 | 🆕 未提交 |
| `service/EventCollectService.java` | 埋点采集逻辑 | ✅ 已提交 |
| `service/IdempotentService.java` | 幂等校验（三层防护） | ✅ 已提交 |
| `service/LoginService.java` | 登录逻辑 | 🆕 未提交 |
| `producer/EventProducer.java` | Kafka 埋点发送 | ✅ 已提交 |
| `producer/OrderSyncProducer.java` | RocketMQ 订单发送 | ✅ 已提交 |
| `fallback/LocalBufferFallback.java` | 本地缓冲兜底 | ✅ 已提交 |
| `CollectorApplication.java` | 启动类 | ✅ 已提交 |

未提交变更：
- `pom.xml` — 新增依赖
- `application.yml` — 配置更新
- `LoginController.java` / `LoginService.java` — 新增登录模块

### 4. ecom-analytics-processor（处理服务 :8082）

**状态：🔧 增强中**

| 文件 | 说明 | 状态 |
|---|---|---|
| `consumer/UserEventConsumer.java` | 消费 Kafka 埋点 | ✅ 已提交 |
| `consumer/OrderSyncConsumer.java` | 消费 RocketMQ 订单 | ✅ 已提交 |
| `config/DataSourceConfig.java` | 多数据源配置 | ✅ 已提交 |
| `service/EventPersistService.java` | 事件持久化 | 📝 已修改 |
| `service/OrderPersistService.java` | 订单持久化 | 📝 已修改 |
| `task/DailyAggregateTask.java` | 每日聚合任务 | 📝 已修改（大幅扩展） |
| `task/JoinTempScanTask.java` | 双流 Join 扫描 | 📝 已修改（大幅扩展） |
| `task/OrderPullTask.java` | 订单拉取任务 | 📝 已修改（大幅扩展） |
| `ProcessorApplication.java` | 启动类 | ✅ 已提交 |

### 5. ecom-analytics-query（查询服务 :8083）

**状态：🔧 增强中**

| 文件 | 说明 | 状态 |
|---|---|---|
| `controller/TrendController.java` | 趋势查询接口 | 📝 已修改 |
| `controller/OperationController.java` | 运营大盘接口 | 🆕 未提交 |
| `controller/RankingController.java` | 排行榜接口 | 🆕 未提交 |
| `service/TrendService.java` | 趋势查询逻辑 | ✅ 已提交 |
| `service/FunnelService.java` | 漏斗分析 | 📝 已修改 |
| `service/OperationService.java` | 运营大盘逻辑 | 🆕 未提交 |
| `service/RankingService.java` | 排行榜逻辑 | 🆕 未提交 |
| `config/DataSourceConfig.java` | 数据源配置 | ✅ 已提交 |
| `config/CacheConfig.java` | Redis 缓存配置 | 🆕 未提交 |
| `dto/TrendPointVO.java` | 趋势数据点 | ✅ 已提交 |
| `dto/FunnelStepVO.java` | 漏斗步骤 | ✅ 已提交 |
| `dto/GmvOverviewVO.java` | GMV 总览 | 🆕 未提交 |
| `dto/GmvTrendPointVO.java` | GMV 趋势数据点 | 🆕 未提交 |
| `dto/CategoryStatVO.java` | 品类统计 | 🆕 未提交 |
| `dto/HotKeywordVO.java` | 热搜词 | 🆕 未提交 |
| `dto/TopItemVO.java` | TOP 商品 | 🆕 未提交 |
| `QueryApplication.java` | 启动类 | ✅ 已提交 |

### 6. ecom-analytics-search（搜索服务 :8084）

**状态：🔧 增强中**

| 文件 | 说明 | 状态 |
|---|---|---|
| `controller/ProductSearchController.java` | 商品搜索接口 | 📝 已修改 |
| `controller/OrderSearchController.java` | 订单搜索接口 | 🆕 未提交 |
| `service/ProductSearchService.java` | 商品搜索逻辑 | 📝 已修改（大幅重写） |
| `service/OrderSearchService.java` | 订单搜索逻辑 | 🆕 未提交 |
| `repository/doc/ProductDoc.java` | 商品 ES 文档 | ✅ 已提交 |
| `repository/doc/OrderEventDoc.java` | 订单事件 ES 文档 | ✅ 已提交 |
| `SearchApplication.java` | 启动类 | ✅ 已提交 |

### 7. ecom-analytics-bigdata（Flink 作业）

**状态：✅ 完成**

| 文件 | 说明 |
|---|---|
| `kafka/EventKafkaProducer.java` | Kafka 生产者 |
| `canal/OrderCanalSyncService.java` | Canal binlog 同步 |
| `flink/EventCleanJob.java` | Flink 清洗作业主类 |

### 8. 文档

| 文件 | 状态 |
|---|---|
| `docs/01-architecture.md` | ✅ 已提交 |
| `docs/02-data-warehouse.md` | ✅ 已提交 |
| `docs/03-database-design.md` | 📝 已修改 |
| `docs/04-api-guide.md` | 📝 已修改 |
| `docs/05-deployment.md` | ✅ 已提交 |
| `docs/06-interview-notes.md` | 📝 已修改 |
| `docs/07-progress.md` | 🆕 本文件 |
| `docs/08-production-upgrade.md` | 🆕 生产化升级方案蓝本（4 个 Stage） |
| `docs/09-runbook.md` | 🆕 生产运行手册（上线 checklist / 排障 SOP / 常用查询） |

### 9. 基础设施（infra）

| 文件 | 状态 |
|---|---|
| `infra/mysql/init.sql` | 📝 已修改（新增表结构） |
| `infra/clickhouse/init.sql` | 📝 已修改（新增表结构） |

---

## 待办事项

- [ ] 将本地所有未提交的变更提交并推送到 GitHub
- [ ] processor 模块的定时任务逻辑完善后补充单元测试
- [ ] query 模块新增的运营大盘 / 排行榜接口联调验证
- [ ] search 模块新增的订单搜索接口联调验证
- [ ] collector 模块登录接口对接认证中心

---

## 变更记录

| 日期 | 变更内容 | 涉及文档 |
|---|---|---|
| 2026-05-16 | 初始化项目骨架，完成全模块基础代码 | 01~06 全部 |
| 2026-05-16 | 新增登录模块（collector） | 04-api-guide |
| 2026-05-16 | 新增运营大盘、排行榜、缓存配置（query） | 03-database-design, 04-api-guide |
| 2026-05-16 | 新增订单搜索（search） | 04-api-guide |
| 2026-05-16 | processor 定时任务大幅扩展 | 06-interview-notes |
| 2026-05-16 | MySQL / ClickHouse 初始化脚本更新 | 03-database-design |
| 2026-05-16 | 发布生产化升级蓝本：P0 基线 / P0 硬伤 / P1 可观测 / P2 部署 | 08-production-upgrade |
| 2026-05-16 | Stage 1 P0 生产基线落地：全局异常+错误码+Knife4j+TraceId+JWT 鉴权 | 08-production-upgrade |
| 2026-05-16 | Stage 2 P0 硬伤修复落地：SQL 注入修复+mergeAndPersist+new_user_cnt+飞书告警 | 08-production-upgrade |
| 2026-05-16 | Stage 3 P1 可观测+稳定性落地：JSON 日志+Prometheus 指标+RocketMQ DLQ+事务消息+缓存三防+多级缓存 | 08-production-upgrade |
| 2026-05-17 | Stage 4 P2 部署+CI/CD+测试+HA 落地：Dockerfile×5+监控栈+K8s+GitHub Actions+Testcontainers+HA 拓扑 | 08-production-upgrade |
| 2026-05-17 | Stage 5.1 k6 压测 + baseline + perf workflow | infra/k6 + .github/workflows/perf.yml |
| 2026-05-17 | Stage 5.2 Sentinel 规则持久化到 Nacos | infra/sentinel + gateway/application.yml |
| 2026-05-17 | Stage 5.3 Grafana 统一告警 + Loki 日志告警 + 飞书通道 | infra/grafana/provisioning/alerting |
| 2026-05-17 | Stage 5.4 ShardingSphere-JDBC 读写分离 + 分表 | infra/shardingsphere + processor/query/sharding-config.yaml |
| 2026-05-17 | Stage 6.1 gen-jwt.sh + 分表物理表 init SQL 补齐 (24 个月) | infra/k6 + infra/mysql/init.sql |
| 2026-05-17 | Stage 6.2 SkyWalking APM 接入 (agent + OAP + UI) | 5 Dockerfile + docker-compose + infra/skywalking |
| 2026-05-17 | Stage 6.3 生产运行手册 (09-runbook.md) | docs/09-runbook |

---

## 文档维护规则

> **每次代码变更后，请按以下规则更新文档：**

| 代码变更类型 | 需要更新的文档 |
|---|---|
| 新增/修改 API 接口 | `04-api-guide.md` |
| 新增/修改数据库表结构 | `03-database-design.md` |
| 新增/修改架构或模块 | `01-architecture.md` |
| 新增/修改数仓分层或 ETL | `02-data-warehouse.md` |
| 修改部署流程或环境配置 | `05-deployment.md` |
| 涉及面试高频知识点 | `06-interview-notes.md` |
| 任何代码变更 | **本文档（07-progress.md）**：更新对应模块的文件列表和状态 |
