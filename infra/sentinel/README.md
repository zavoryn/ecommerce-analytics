# Sentinel 规则持久化 (Nacos)

## 背景

默认情况下 Sentinel Dashboard 修改的规则只存在内存, 应用重启或新实例上线时规则丢失。
本项目接入 **`sentinel-datasource-nacos`**, 让规则持久化到 Nacos 配置中心:

```
        ┌─────────────────┐
        │  Sentinel Dashboard │   人工修改规则
        └────────┬────────┘
                 │ push (DataSource 双向)
                 ▼
        ┌─────────────────┐
        │      Nacos      │   持久化 + 多实例共享
        │  SENTINEL_GROUP │
        └────────┬────────┘
                 │ ReadableDataSource 监听 + 实时拉取
                 ▼
        ┌─────────────────┐
        │  Gateway 实例 ×N │   动态生效, 无需重启
        └─────────────────┘
```

## Nacos 中规则 data-id 约定

| Data ID | Group | rule-type | 用途 |
|---|---|---|---|
| `gateway-flow-rules.json`    | SENTINEL_GROUP | gw-flow      | 网关流控规则 (按 route / api) |
| `gateway-api-rules.json`     | SENTINEL_GROUP | gw-api-group | 自定义 API 分组定义 |
| `gateway-degrade-rules.json` | SENTINEL_GROUP | degrade      | 网关熔断降级 |
| `processor-flow-rules.json`  | SENTINEL_GROUP | flow         | 消费端落库限流 (event:persist / order:upsert) |
| `processor-degrade-rules.json`| SENTINEL_GROUP | degrade      | 消费端 RT 熔断 (面试稿 2.5: 下游 DB RT 飙升触发) |
| `query-flow-rules.json`      | SENTINEL_GROUP | flow         | 查询接口限流 (topItems / hotKeywords / topCategories) |
| `query-degrade-rules.json`   | SENTINEL_GROUP | degrade      | 查询接口慢调用熔断 / 异常比例熔断 |

## 初始化规则示例

把以下 JSON 内容分别发布到 Nacos 对应 Data ID:

### gateway-flow-rules.json

```json
[
  {
    "resource": "collector",
    "count": 2000,
    "intervalSec": 1,
    "controlBehavior": 0,
    "burst": 0
  },
  {
    "resource": "query",
    "count": 400,
    "intervalSec": 1
  },
  {
    "resource": "ranking",
    "count": 400,
    "intervalSec": 1
  },
  {
    "resource": "search",
    "count": 600,
    "intervalSec": 1
  }
]
```

> `resource` 对应 `application.yml` 中 spring.cloud.gateway.routes[*].id

### gateway-api-rules.json

```json
[
  {
    "apiName": "ranking-hot",
    "predicateItems": [
      { "pattern": "/api/ranking/top-items", "matchStrategy": 0 },
      { "pattern": "/api/ranking/hot-keywords", "matchStrategy": 0 }
    ]
  }
]
```

### gateway-degrade-rules.json

```json
[
  {
    "resource": "query",
    "grade": 0,
    "count": 1000,
    "timeWindow": 10,
    "minRequestAmount": 20,
    "statIntervalMs": 10000,
    "slowRatioThreshold": 0.5
  }
]
```

`grade=0` 表示按慢调用比例熔断: P95 > 1000ms 且 20 个请求中超过 50% 慢调用 → 熔断 10s。

## 发布规则到 Nacos

### 方式 1: Nacos 控制台 (推荐)

1. 浏览器打开 http://localhost:8848/nacos (admin/nacos)
2. 配置管理 → 配置列表 → 新建配置
3. Data ID 填上面表格中的值, Group 选 `SENTINEL_GROUP`
4. 配置内容粘上面的 JSON
5. 点击 `发布`

### 方式 2: 命令行 (curl)

```bash
curl -X POST 'http://localhost:8848/nacos/v1/cs/configs' \
  -d 'dataId=gateway-flow-rules.json' \
  -d 'group=SENTINEL_GROUP' \
  -d 'type=json' \
  --data-urlencode 'content=[{"resource":"collector","count":2000,"intervalSec":1}]'
```

### 方式 3: Sentinel Dashboard (双向同步)

通过 dashboard 修改规则后, 我们的代码改造已支持 push 回 Nacos (需要额外的 push 适配器,
本项目当前为单向 pull, 即 Nacos 是源真值。改规则请走 Nacos 控制台)。

## 验证

```bash
# 1. 启动 nacos + 应用
docker compose up -d nacos
mvn -DskipTests package
java -jar ecom-analytics-gateway/target/*.jar

# 2. 发布规则到 Nacos (见上文)

# 3. 验证: 用 k6 压一下, 看是否被限到 count 设定
cd infra/k6
k6 run --vus 500 --duration 30s collect-event.js
# 若限流生效, 应看到大量 429 / 自定义降级响应
```

## 注意事项

- **第一次启动时**, 如果 Nacos 里还没规则, gateway 启动正常但限流不生效(默认放行)。请提前发布规则。
- **Sentinel Dashboard 与 Nacos**: 本项目是单向同步(Nacos → app), Dashboard 看到的规则是只读;
  生产强一致需要安装 Sentinel 控制台的 push 适配器 (`spring-cloud-alibaba-sentinel-datasource` 提供的 NacosWritableDataSource)。
- **规则热更新**: 修改 Nacos 配置后, 默认 10s 内全实例自动拉到新规则, 无需重启。
