-- =============================================================
-- ecom-analytics ClickHouse 初始化脚本
--
-- ClickHouse 是埋点数据的最终分析库（OLAP 层）
-- 前端所有行为数据（浏览/点击/加购/支付）最终都落这里
-- MySQL 只存核心交易数据（订单/支付），职责严格隔离
--
-- 核心引擎: MergeTree（面试稿 1.4.4）
--   - 稀疏索引: 每 8192 行一个 Granule，索引全量放内存
--   - SIMD 向量化: AVX-512 指令集，一次时钟处理一批数字
--   - LSM-Tree 变种写入: 顺序追加 Part，后台 Merge
-- =============================================================

CREATE DATABASE IF NOT EXISTS ecom_analytics;

-- -------------------------------------------------------------
-- 1. 事件明细大表（核心表）
--    所有前端埋点事件落这里，PB 级数据毫秒级聚合
--
--    ORDER BY (device_id, event_date, event_time) 说明:
--      - 同一设备的事件物理上相邻存储
--      - windowFunnel 按 device_id 分组时，数据本地化，IO 极小
-- -------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ecom_analytics.events_local
(
    `event_date`  Date        NOT NULL COMMENT '分区键',
    `event_time`  DateTime    NOT NULL COMMENT '精确到秒的事件时间',
    `device_id`   String      NOT NULL COMMENT '设备指纹（未登录唯一标识）',
    `user_id`     UInt64      DEFAULT 0 COMMENT '用户ID，0表示未登录',
    `session_id`  String      NOT NULL COMMENT '会话ID，生命周期30分钟',
    `event_name`  LowCardinality(String) COMMENT '事件名（低基数，存储压缩极高）',
    `item_id`     UInt64      DEFAULT 0 COMMENT '商品ID',
    `category`    LowCardinality(String) DEFAULT '' COMMENT '商品类目',
    `order_id`    UInt64      DEFAULT 0 COMMENT '关联订单ID（pay_order事件时有值）',
    `page_source` LowCardinality(String) DEFAULT '' COMMENT '来源页面',
    `properties`  String      DEFAULT '{}' COMMENT '扩展属性JSON',
    `os`          LowCardinality(String) DEFAULT '',
    `app_version` String      DEFAULT '',
    `network`     LowCardinality(String) DEFAULT ''
)
ENGINE = MergeTree()
PARTITION BY toYYYYMM(event_date)    -- 按月分区，老数据自动 TTL
ORDER BY (device_id, event_date, event_time)
TTL event_date + INTERVAL 180 DAY   -- 180天自动清理
SETTINGS index_granularity = 8192;  -- 每 8192 行一个稀疏索引条目

-- -------------------------------------------------------------
-- 2. 漏斗分析物化视图（可选，提前聚合加速）
--    windowFunnel 函数会在查询时实时计算，无需物化视图也可以
--    但高频漏斗查询建议用 AggregatingMergeTree 物化
-- -------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ecom_analytics.funnel_agg_state
(
    `event_date`    Date,
    `device_id`     String,
    `funnel_state`  AggregateFunction(windowFunnel(7200),  -- 2小时窗口
                        DateTime,
                        UInt8, UInt8, UInt8, UInt8)        -- 4步漏斗
)
ENGINE = AggregatingMergeTree()
PARTITION BY toYYYYMM(event_date)
ORDER BY (event_date, device_id);

-- 对应物化视图（从 events_local 实时聚合，生产环境按需开启）
-- CREATE MATERIALIZED VIEW IF NOT EXISTS ecom_analytics.funnel_agg_mv
-- TO ecom_analytics.funnel_agg_state
-- AS SELECT
--     event_date,
--     device_id,
--     windowFunnelState(7200)(event_time,
--         event_name = 'search',
--         event_name = 'view_item',
--         event_name = 'add_cart',
--         event_name = 'pay_order') AS funnel_state
-- FROM ecom_analytics.events_local
-- GROUP BY event_date, device_id;

-- -------------------------------------------------------------
-- 3. 数仓 DWD 层宽表
--    Flink 做行为-订单双流 Join 后落这里（面试稿 2.4 最终答案）
--    一行 = 一个用户的完整购买链路，后续分析不再 Join
-- -------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ecom_analytics.dwd_user_order_action
(
    `event_date`   Date,
    `user_id`      UInt64,
    `device_id`    String,
    `session_id`   String,
    `item_id`      UInt64,
    `category`     LowCardinality(String),
    -- 行为侧时间戳
    `search_time`  Nullable(DateTime)  COMMENT '搜索时间',
    `view_time`    Nullable(DateTime)  COMMENT '浏览时间',
    `cart_time`    Nullable(DateTime)  COMMENT '加购时间',
    `pay_time`     Nullable(DateTime)  COMMENT '支付时间',
    -- 订单侧
    `order_id`     UInt64      DEFAULT 0,
    `order_amount` Float64     DEFAULT 0,
    `order_status` Int8        DEFAULT 0
)
ENGINE = MergeTree()
PARTITION BY toYYYYMM(event_date)
ORDER BY (user_id, event_date, item_id);

-- -------------------------------------------------------------
-- 4. DWS 层每日汇总（对应 MySQL event_agg_daily，高性能版本）
--    按 (event_date, item_id) 聚合，PV/UV/GMV 直接出结果
-- -------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ecom_analytics.dws_item_daily
(
    `event_date`   Date,
    `item_id`      UInt64,
    `category`     LowCardinality(String),
    `pv`           UInt64 DEFAULT 0,
    `uv`           UInt64 DEFAULT 0   COMMENT '基于 device_id HLL 估算（面试稿 1.3）',
    `add_cart_cnt` UInt64 DEFAULT 0,
    `pay_cnt`      UInt64 DEFAULT 0,
    `gmv`          Float64 DEFAULT 0
)
ENGINE = SummingMergeTree()
PARTITION BY toYYYYMM(event_date)
ORDER BY (event_date, item_id);

-- -------------------------------------------------------------
-- 5. DWS 类目每日汇总（SummingMergeTree，支持增量写入自动累加）
--    运营大盘类目分析、类目 GMV 趋势来源
-- -------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ecom_analytics.dws_category_daily
(
    `event_date`      Date,
    `category`        LowCardinality(String),
    `pv`              UInt64  DEFAULT 0,
    `uv`              UInt64  DEFAULT 0  COMMENT 'HLL 估算, 准确 UV 走 MySQL category_daily',
    `search_cnt`      UInt64  DEFAULT 0,
    `add_cart_cnt`    UInt64  DEFAULT 0,
    `create_order_cnt` UInt64 DEFAULT 0,
    `pay_cnt`         UInt64  DEFAULT 0,
    `pay_amount`      Float64 DEFAULT 0,
    `gmv`             Float64 DEFAULT 0,
    `item_cnt`        UInt64  DEFAULT 0  COMMENT '有事件的SKU去重数(近似)'
)
ENGINE = SummingMergeTree()
PARTITION BY toYYYYMM(event_date)
ORDER BY (event_date, category);

-- -------------------------------------------------------------
-- 6. DWS 热搜词每日汇总（实时/离线双写，供趋势分析）
-- -------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ecom_analytics.dws_search_keyword_daily
(
    `event_date`  Date,
    `keyword`     String,
    `search_cnt`  UInt64  DEFAULT 0,
    `uv`          UInt64  DEFAULT 0   COMMENT '搜索人数(HLL近似)',
    `click_cnt`   UInt64  DEFAULT 0,
    `pay_cnt`     UInt64  DEFAULT 0,
    `pay_amount`  Float64 DEFAULT 0
)
ENGINE = SummingMergeTree()
PARTITION BY toYYYYMM(event_date)
ORDER BY (event_date, keyword);

-- -------------------------------------------------------------
-- 查询示例（写在注释里，方便快速理解和 Demo）
-- -------------------------------------------------------------

-- 示例1: 漏斗分析 search->view->add_cart->pay（面试稿 1.2）
-- SELECT
--     level,
--     count() AS user_cnt
-- FROM (
--     SELECT
--         device_id,
--         windowFunnel(7200)(event_time,
--             event_name = 'search',
--             event_name = 'view_item',
--             event_name = 'add_cart',
--             event_name = 'pay_order') AS level
--     FROM ecom_analytics.events_local
--     WHERE event_date BETWEEN '2025-01-01' AND '2025-01-07'
--     GROUP BY device_id
-- )
-- GROUP BY level
-- ORDER BY level DESC;

-- 示例2: 近30天商品UV（HLL 近似去重，内存极省，面试稿 1.3）
-- SELECT
--     item_id,
--     uniqHLL12(device_id) AS uv
-- FROM ecom_analytics.events_local
-- WHERE event_date >= today() - 30
--   AND event_name = 'view_item'
-- GROUP BY item_id
-- ORDER BY uv DESC
-- LIMIT 10;

-- 示例3: 实时 GMV（毫秒级，面试稿 1.4）
-- SELECT
--     toDate(event_time) AS dt,
--     sum(order_amount)  AS gmv
-- FROM ecom_analytics.dwd_user_order_action
-- WHERE event_date >= today() - 7
--   AND order_status = 1
-- GROUP BY dt
-- ORDER BY dt;

-- 示例4: 热搜词 TOP20（实时统计，面试稿 1.4 / 运营选品场景）
-- SELECT
--     JSONExtractString(properties, 'keyword') AS keyword,
--     count()                                  AS search_cnt,
--     uniq(device_id)                          AS uv
-- FROM ecom_analytics.events_local
-- WHERE event_name = 'search'
--   AND event_date = today()
--   AND notEmpty(JSONExtractString(properties, 'keyword'))
-- GROUP BY keyword
-- ORDER BY search_cnt DESC
-- LIMIT 20;

-- 示例5: 类目实时大盘 PV/UV（按类目聚合）
-- SELECT
--     category,
--     count()         AS pv,
--     uniq(device_id) AS uv,
--     countIf(event_name = 'add_cart') AS add_cart_cnt,
--     countIf(event_name = 'pay_order') AS pay_cnt
-- FROM ecom_analytics.events_local
-- WHERE event_date = today()
--   AND notEmpty(category)
-- GROUP BY category
-- ORDER BY pv DESC
-- LIMIT 20;

-- 示例6: 单商品转化漏斗（面试稿专项漏斗场景）
-- SELECT
--     level,
--     count() AS user_cnt
-- FROM (
--     SELECT
--         device_id,
--         windowFunnel(7200)(event_time,
--             event_name = 'view_item',
--             event_name = 'add_cart',
--             event_name = 'create_order',
--             event_name = 'pay_order') AS level
--     FROM ecom_analytics.events_local
--     WHERE item_id = 888
--       AND event_date BETWEEN '2025-01-01' AND '2025-01-07'
--     GROUP BY device_id
-- )
-- GROUP BY level ORDER BY level DESC;
