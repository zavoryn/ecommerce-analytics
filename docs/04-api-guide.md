# API 接口说明

所有接口统一经过 Gateway（:8080）路由，Swagger 文档地址：`http://localhost:8080/doc.html`

---

## 1. 数据采集接口（collector :8081）

### 1.1 上报用户行为事件

```
POST /api/collect/event
Content-Type: application/json
```

**请求体**（对应埋点规范 DRD）：
```json
{
  "requestId": "550e8400-e29b-41d4-a716-446655440000",
  "deviceId":  "d_abc123",
  "userId":    10086,
  "sessionId": "sess_xyz789",
  "eventName": "add_cart",
  "timestamp": 1705296000000,
  "os":        "iOS",
  "appVersion":"2.1.0",
  "network":   "WiFi",
  "properties": {
    "item_id":    888,
    "category":   "女装",
    "price":      299.0,
    "keyword":    "连衣裙",
    "page_source":"home_feed",
    "order_id":   null
  }
}
```

**eventName 枚举**：

| 值 | 含义 | properties 必填字段 |
|---|---|---|
| `view_page` | 页面浏览 | page_source |
| `view_item` | 商品曝光/浏览 | item_id, category, price |
| `search` | 搜索 | keyword |
| `click` | 点击 | item_id, page_source |
| `add_cart` | 加入购物车 | item_id, category, price |
| `create_order` | 创建订单 | item_id, order_id, order_amount |
| `pay_order` | 支付成功 | item_id, order_id, paid_amount |
| `cancel_order` | 取消订单 | order_id |
| `refund` | 申请退款 | order_id, refund_amount |

**响应**：
```json
{ "code": 0, "msg": "ok", "data": null, "timestamp": 1705296001234 }
```

### 1.2 批量上报

```
POST /api/collect/event/batch
Body: [{ ...event1 }, { ...event2 }]
```

最大 500 条/批，Gateway 限流 1000 QPS/IP。

### 1.3 订单同步（订单系统主动推送）

```
POST /api/sync/order
Body:
{
  "orderId": 666,
  "userId": 10086,
  "itemId": 888,
  "category": "女装",
  "quantity": 1,
  "orderAmount": 299.0,
  "paidAmount": 259.0,
  "orderStatus": 1,
  "version": 3,
  "orderTime": "2025-01-15T10:30:00",
  "updateTime": "2025-01-15T10:31:00"
}
```

---

## 2. 查询接口（query :8083）

### 2.1 商品趋势

```
GET /api/query/item-trend?itemId=888&days=7
```

**响应**：
```json
{
  "code": 0,
  "data": [
    { "date": "2025-01-09", "pv": 12800, "uv": 4320, "payCnt": 88 },
    { "date": "2025-01-10", "pv": 15600, "uv": 5100, "payCnt": 102 }
  ]
}
```

**查询逻辑（三段式，面试稿 2.10）**：
```
1. 查 event_agg_daily（聚合表）获取 days-1 天历史
   └─ WHERE item_id=? AND event_date BETWEEN ? AND yesterday
   └─ 命中 idx_item_date，< 50ms

2. 判断今日数据是否已聚合
   └─ 未聚合 → 查当月明细表实时补今日 1 行
   └─ 已聚合 → 直接用聚合表数据

3. Redis 缓存整体结果（TTL=10min）
   └─ 100个运营同时查同一商品 → 只有第 1 个查 DB
```

### 2.2 全平台漏斗分析

```
GET /api/query/funnel?fromDate=2025-01-08&toDate=2025-01-15&windowSeconds=7200
```

**响应**（ClickHouse windowFunnel 计算结果）：
```json
{
  "code": 0,
  "data": [
    { "step": "search",    "userCount": 100000, "conversionRate": 1.0  },
    { "step": "view_item", "userCount": 65000,  "conversionRate": 0.65 },
    { "step": "add_cart",  "userCount": 28000,  "conversionRate": 0.43 },
    { "step": "pay_order", "userCount": 9800,   "conversionRate": 0.35 }
  ]
}
```

### 2.3 单商品转化漏斗（运营选品专项）

```
GET /api/query/item-funnel?itemId=888&fromDate=2025-01-08&toDate=2025-01-15
```

**场景**：运营发现连衣裙 888 PV 高但 GMV 低，诊断具体卡在哪步。

**响应**：
```json
{
  "code": 0,
  "data": [
    { "step": "view_item",     "userCount": 5000, "conversionRate": 1.00 },
    { "step": "add_cart",      "userCount": 1500, "conversionRate": 0.30 },
    { "step": "create_order",  "userCount": 600,  "conversionRate": 0.40 },
    { "step": "pay_order",     "userCount": 500,  "conversionRate": 0.83 }
  ]
}
```

加购率仅 30% → 说明商品详情页或价格存在问题，运营决策：优化主图/降价促销。

---

## 3. 运营大盘接口（query :8083）

### 3.1 GMV 大盘概览

```
GET /api/operation/overview
```

**场景**：运营早会打开大屏，查看今日实时数据和环比。

**响应**：
```json
{
  "code": 0,
  "data": {
    "todayGmv":          158600.50,
    "todayOrderCnt":     1230,
    "todayPayUserCnt":   980,
    "todayUv":           45200,
    "todayConversionRate": 0.0272,
    "yesterdayGmv":      142300.00,
    "dayOnDayRate":      0.1146,
    "monthGmv":          3820000.00,
    "monthOrderCnt":     28600,
    "monthRefundAmount": 45200.00,
    "monthNetGmv":       3774800.00,
    "todayArpu":         161.84
  }
}
```

**字段说明**：
- `dayOnDayRate`: 正数表示增长（0.1146 = +11.46%），负数表示下跌
- `todayConversionRate`: 今日支付转化率（订单数/UV）
- `todayArpu`: 今日每付费用户平均消费额

### 3.2 GMV 趋势

```
GET /api/operation/gmv-trend?days=7
GET /api/operation/gmv-trend?days=30
GET /api/operation/gmv-trend?days=90
```

**场景**：复盘大促效果、判断业务走势。

**响应**：
```json
{
  "code": 0,
  "data": [
    {
      "date": "2025-01-09",
      "gmv": 142300.00,
      "orderCnt": 1102,
      "payUserCnt": 890,
      "uv": 38600,
      "payRate": 0.0231,
      "refundAmount": 3200.00
    }
  ]
}
```

**优先级**：`platform_daily`（预计算，< 10ms）→ 降级 `order_sync GROUP BY date`（实时聚合）

### 3.3 类目统计分析

```
GET /api/operation/category-stats?fromDate=2025-01-08&toDate=2025-01-15
```

**场景**：选品会议，横向对比各类目流量/转化/GMV。

**响应**：
```json
{
  "code": 0,
  "data": [
    {
      "category":      "女装",
      "pv":            380000,
      "uv":            92000,
      "searchCnt":     45000,
      "addCartCnt":    28000,
      "createOrderCnt": 8500,
      "payCnt":        7200,
      "payAmount":     2160000.00,
      "gmv":           2250000.00,
      "itemCnt":       1280,
      "gmvRate":       0.3820,
      "cartRate":      0.0737,
      "payRate":       0.0783
    },
    {
      "category":   "数码",
      "gmvRate":    0.2140,
      "cartRate":   0.0420,
      "payRate":    0.1230
    }
  ]
}
```

**选品决策示例**：
- 女装 `cartRate=0.07` < 数码 `cartRate=0.04` 说明女装用户更爱加购但转化差 → 需优化支付流程/减少结算阻力
- 数码 `payRate=0.12` >> 女装 `payRate=0.08` 说明数码用户决策快，适合闪购活动

---

## 4. 排行榜接口（query :8083）

### 4.1 TOP 商品排行

```
# 全平台 TOP10 GMV 商品（大促备货参考）
GET /api/ranking/top-items?rankBy=gmv&days=7&limit=10

# 女装类目 TOP20 销量（运营选款）
GET /api/ranking/top-items?rankBy=pay_cnt&days=30&category=女装&limit=20

# 高流量低转化排查（PV 高 payRate 低的商品需要优化）
GET /api/ranking/top-items?rankBy=pv&days=7&limit=50
```

**rankBy 枚举**：

| 值 | 含义 | 使用场景 |
|---|---|---|
| `gmv` | 实付金额（默认） | 大促备货、GMV贡献分析 |
| `pay_cnt` | 支付笔数 | 爆款潜力、流水商品 |
| `pv` | 浏览量 | 流量热点、高曝光低转化诊断 |
| `add_cart_cnt` | 加购次数 | 潜力爆款（加购多但支付少的商品需促销刺激） |

**响应**：
```json
{
  "code": 0,
  "data": [
    {
      "rank":       1,
      "prevRank":   3,
      "rankChange": "↑2",
      "itemId":     888,
      "itemName":   "夏季修身连衣裙",
      "category":   "女装",
      "brand":      "ZARA",
      "gmv":        128000.00,
      "payCnt":     430,
      "pv":         38500,
      "uv":         22000,
      "addCartCnt": 5200,
      "cartRate":   0.1351,
      "payRate":    0.0195
    }
  ]
}
```

**数据优先级**：
1. `item_ranking_daily`（凌晨预计算，< 5ms）
2. 降级：实时聚合 `order_sync JOIN product_info JOIN event_agg_daily`

### 4.2 热搜词排行

```
# 今日实时热词（CK 实时，秒级数据）
GET /api/ranking/hot-keywords

# 昨日热词（含完整 CTR/CVR，MySQL T+1 聚合）
GET /api/ranking/hot-keywords?date=2025-01-14&limit=20
```

**响应**：
```json
{
  "code": 0,
  "data": [
    {
      "rank":      1,
      "keyword":   "连衣裙",
      "searchCnt": 28500,
      "uv":        18200,
      "clickCnt":  12800,
      "payCnt":    3200,
      "payAmount": 921600.00,
      "ctr":       0.4491,
      "cvr":       0.1758,
      "trend":     "rising"
    },
    {
      "rank":    2,
      "keyword": "短袖T恤",
      "trend":   "stable"
    }
  ]
}
```

**trend 说明**：
- `rising`：今日搜索量 > 昨日同期 1.5 倍（飙升词，运营立即跟进补货）
- `falling`：今日搜索量 < 昨日同期 0.7 倍（热度下降）
- `new`：昨日无此词（新兴词，可能是热点事件带动）
- `stable`：正常波动

### 4.3 TOP 类目排行

```
GET /api/ranking/top-categories?days=7&limit=10
GET /api/ranking/top-categories?days=30&limit=10
```

**场景**：流量分配决策——哪个品类 GMV 贡献大，应该获得更多首页资源位。

**响应**：
```json
{
  "code": 0,
  "data": [
    {
      "category": "女装",
      "gmv":      2250000.00,
      "gmvRate":  0.3820,
      "pv":       380000,
      "uv":       92000,
      "payCnt":   7200,
      "cartRate": 0.0737,
      "payRate":  0.0783
    }
  ]
}
```

---

## 5. 搜索接口（search :8084）

### 5.1 商品搜索（ES search_after 深分页）

```
# 首页（不传 searchAfter）
GET /api/search/product?keyword=连衣裙&category=女装&minPrice=100&maxPrice=500&pageSize=20

# 翻下一页（传上一页返回的 nextSearchAfter）
GET /api/search/product?keyword=连衣裙&pageSize=20&searchAfter=1200&searchAfter=888
```

**响应**：
```json
{
  "code": 0,
  "data": {
    "items": [
      {
        "itemId": 888,
        "title":  "夏季修身连衣裙",
        "category": "女装",
        "brand":  "ZARA",
        "price":  299.0,
        "sales30d": 1200,
        "status": 1
      }
    ],
    "nextSearchAfter": ["1200", "888"]
  }
}
```

**search_after 深分页原理**（面试稿 1.5 / 2.7）：
```
普通 LIMIT 10000, 20:
  ES 每个分片拉出 10020 条 → 汇总排序 → 丢弃前 10000 → 返回 20 条
  问题：随页数线性增长，翻到深页极慢且内存压力大

search_after:
  第 N 页：传入第 N-1 页最后一条的 sort 值 [sales30d=1200, itemId=888]
  ES 直接从游标处取 20 条，不管第几页性能恒定
  约束：不支持任意跳页，只支持"上一页/下一页"
```

---

## 6. 错误码规范

| code | 含义 |
|---|---|
| 0 | 成功 |
| 400 | 参数错误（缺少必填参数、格式不对） |
| 429 | 限流（Sentinel 触发） |
| 500 | 服务内部错误 |
| 503 | 服务降级/熔断（依赖服务不可用） |

---

## 7. 接口限流规则（Sentinel）

| 接口路径 | QPS/IP | 说明 |
|---|---|---|
| `POST /api/collect/event` | 1000 | 大促时可临时调高 |
| `POST /api/collect/event/batch` | 200 | 批量接口限制更严 |
| `GET /api/query/item-trend` | 200 | Redis 缓存挡大部分 |
| `GET /api/query/funnel` | 50 | CK 计算较重 |
| `GET /api/query/item-funnel` | 50 | CK 计算较重 |
| `GET /api/operation/overview` | 200 | 运营内部系统 |
| `GET /api/operation/gmv-trend` | 100 | 30min 缓存 |
| `GET /api/operation/category-stats` | 100 | 60min 缓存 |
| `GET /api/ranking/top-items` | 200 | 10min 缓存 |
| `GET /api/ranking/hot-keywords` | 200 | 今日实时走 CK |
| `GET /api/ranking/top-categories` | 200 | 走聚合表 |
| `GET /api/search/product` | 300 | ES 有自身限流 |

---

## 8. Redis 缓存 Key 规范

| 接口 | Cache Key 格式 | TTL |
|---|---|---|
| 商品趋势 | `itemTrend::{itemId}:{days}` | 10min |
| 全平台漏斗 | `funnel::{fromDate}:{toDate}` | 30min |
| GMV 趋势 | `gmvTrend::{days}` | 30min |
| 类目统计 | `categoryStats::{fromDate}:{toDate}` | 60min |
| TOP 商品 | `topItems::{rankBy}:{days}:{category}:{limit}` | 10min |
| 热搜词 | `hotKeywords::{date}:{limit}` | 5min（今日实时）/ 60min（历史） |
| TOP 类目 | `topCategories::{days}:{limit}` | 10min |

---

## 9. 接口数据流向图

```
运营查 GMV 概览
   │
   ├── 今日实时 GMV ─────── order_sync（MySQL，精确口径）
   ├── 今日 UV ───────────── events_local（ClickHouse，实时 uniq）
   └── 本月退款 ──────────── order_sync（order_status=3）

运营查 TOP 商品
   │
   ├── 优先路径 ──────────── item_ranking_daily（预计算，< 5ms）
   └── 降级路径 ──────────── order_sync JOIN product_info JOIN event_agg_daily

运营查热搜词
   │
   ├── 今日实时 ──────────── events_local（CK，windowFunnel 实时）
   └── 历史日期 ──────────── search_keyword_daily（MySQL T+1）

搜索商品
   └── ES product_index（IK 分词 + Roaring Bitmap 多条件过滤 + search_after）
```
