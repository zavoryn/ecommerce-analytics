-- =============================================================
-- ecom-analytics MySQL 初始化脚本
-- 数据库: ecom_analytics
-- 说明:
--   MySQL 只存核心交易数据(订单/同步状态)和聚合结果。
--   埋点行为明细的最终存储在 ClickHouse，MySQL 这里只是过渡中间层。
-- =============================================================

CREATE DATABASE IF NOT EXISTS ecom_analytics DEFAULT CHARACTER SET utf8mb4;
USE ecom_analytics;

-- -------------------------------------------------------------
-- 1. 用户行为明细表（按月分表，示例建 2 个月）
--    命名规则: event_detail_YYYYMM
--
--    设计要点（面试稿 1.6 / 2.6）:
--      - ext_info JSON 字段做无感扩展，新业务字段不需要 ALTER TABLE
--      - 虚拟列 item_id_vi 为 ext_info.item_id 建索引，避免 JSON 查询全表扫描
--      - 唯一键 uk_idempotent 保证幂等（device_id + event_name + ts + biz_key）
-- -------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `event_detail_202501` (
    `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `request_id`  VARCHAR(64)     NOT NULL COMMENT '请求ID，用于幂等',
    `device_id`   VARCHAR(64)     NOT NULL COMMENT '设备指纹，未登录用户唯一标识',
    `user_id`     BIGINT UNSIGNED          COMMENT '用户ID，登录后才有',
    `session_id`  VARCHAR(64)     NOT NULL COMMENT '会话ID，用于漏斗链路串联',
    `event_name`  VARCHAR(32)     NOT NULL COMMENT '事件名: view_item/add_cart/pay_order...',
    `ts`          DATETIME(3)     NOT NULL COMMENT '客户端事件时间',
    `os`          VARCHAR(16)              COMMENT '操作系统',
    `app_version` VARCHAR(16)              COMMENT 'App版本',
    `network`     VARCHAR(16)              COMMENT '网络类型',
    `ext_info`    JSON                     COMMENT '业务扩展字段: item_id/category/price/order_id...',
    -- 虚拟列，为 ext_info.item_id 建索引（MySQL 5.7+ Generated Column）
    `item_id_vi`  BIGINT GENERATED ALWAYS AS (CAST(`ext_info` ->> '$.item_id' AS UNSIGNED)) VIRTUAL,
    `created_at`  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    -- 唯一键兜底幂等（面试稿 2.6：Redis 宕机时 DB 唯一键兜底）
    UNIQUE KEY `uk_idempotent` (`request_id`),
    -- 联合索引覆盖高频查询: WHERE ts BETWEEN x AND y AND item_id = ?
    KEY `idx_ts_item` (`ts`, `item_id_vi`),
    KEY `idx_device_ts` (`device_id`, `ts`),
    KEY `idx_session` (`session_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户行为明细-202501';

-- 复制同样结构给 202502 ~ 202612（24 个月）
-- 生产中由 scheduled task 提前 1 个月自动建表(创建下个月表 + drop 18 个月前历史表);
-- 这里全量预建是为了 ShardingSphere INTERVAL 算法的 actualDataNodes 落到实表。
CREATE TABLE IF NOT EXISTS `event_detail_202502` LIKE `event_detail_202501`;
CREATE TABLE IF NOT EXISTS `event_detail_202503` LIKE `event_detail_202501`;
CREATE TABLE IF NOT EXISTS `event_detail_202504` LIKE `event_detail_202501`;
CREATE TABLE IF NOT EXISTS `event_detail_202505` LIKE `event_detail_202501`;
CREATE TABLE IF NOT EXISTS `event_detail_202506` LIKE `event_detail_202501`;
CREATE TABLE IF NOT EXISTS `event_detail_202507` LIKE `event_detail_202501`;
CREATE TABLE IF NOT EXISTS `event_detail_202508` LIKE `event_detail_202501`;
CREATE TABLE IF NOT EXISTS `event_detail_202509` LIKE `event_detail_202501`;
CREATE TABLE IF NOT EXISTS `event_detail_202510` LIKE `event_detail_202501`;
CREATE TABLE IF NOT EXISTS `event_detail_202511` LIKE `event_detail_202501`;
CREATE TABLE IF NOT EXISTS `event_detail_202512` LIKE `event_detail_202501`;
CREATE TABLE IF NOT EXISTS `event_detail_202601` LIKE `event_detail_202501`;
CREATE TABLE IF NOT EXISTS `event_detail_202602` LIKE `event_detail_202501`;
CREATE TABLE IF NOT EXISTS `event_detail_202603` LIKE `event_detail_202501`;
CREATE TABLE IF NOT EXISTS `event_detail_202604` LIKE `event_detail_202501`;
CREATE TABLE IF NOT EXISTS `event_detail_202605` LIKE `event_detail_202501`;
CREATE TABLE IF NOT EXISTS `event_detail_202606` LIKE `event_detail_202501`;
CREATE TABLE IF NOT EXISTS `event_detail_202607` LIKE `event_detail_202501`;
CREATE TABLE IF NOT EXISTS `event_detail_202608` LIKE `event_detail_202501`;
CREATE TABLE IF NOT EXISTS `event_detail_202609` LIKE `event_detail_202501`;
CREATE TABLE IF NOT EXISTS `event_detail_202610` LIKE `event_detail_202501`;
CREATE TABLE IF NOT EXISTS `event_detail_202611` LIKE `event_detail_202501`;
CREATE TABLE IF NOT EXISTS `event_detail_202612` LIKE `event_detail_202501`;

-- -------------------------------------------------------------
-- 2. 每日聚合表
--    定时任务每天凌晨从明细表聚合写入（面试稿 2.7 查询优化核心）
--    30天趋势查询走这张表，响应从秒级降到 100ms
-- -------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `event_agg_daily` (
    `id`           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `event_date`   DATE            NOT NULL COMMENT '统计日期',
    `item_id`      BIGINT UNSIGNED NOT NULL COMMENT '商品ID',
    `category`     VARCHAR(64)              COMMENT '类目',
    `brand`        VARCHAR(64)              COMMENT '品牌',
    `pv`           BIGINT          NOT NULL DEFAULT 0 COMMENT '页面浏览量(view_item事件数)',
    `uv`           BIGINT          NOT NULL DEFAULT 0 COMMENT '独立访客数(按device_id去重)',
    `search_cnt`   BIGINT          NOT NULL DEFAULT 0 COMMENT '搜索到该商品的次数',
    `add_cart_cnt` BIGINT          NOT NULL DEFAULT 0 COMMENT '加购次数',
    `create_order_cnt` BIGINT      NOT NULL DEFAULT 0 COMMENT '创建订单次数',
    `pay_cnt`      BIGINT          NOT NULL DEFAULT 0 COMMENT '支付笔数',
    `pay_amount`   DECIMAL(15,2)   NOT NULL DEFAULT 0 COMMENT '实付金额(来自order_sync join)',
    `gmv`          DECIMAL(15,2)   NOT NULL DEFAULT 0 COMMENT '成交金额GMV口径(含未支付)',
    `updated_at`   DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    -- 联合唯一键 + 联合索引，优先走这个查 item 近 N 天趋势
    UNIQUE KEY `uk_date_item` (`event_date`, `item_id`),
    KEY `idx_item_date` (`item_id`, `event_date`),
    KEY `idx_date_category` (`event_date`, `category`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户行为每日聚合表(商品维度)';

-- -------------------------------------------------------------
-- 3. 订单同步表
--    从订单系统同步过来的销售数据（面试稿 2.2 兜底 + 2.5 版本号）
--    设计要点:
--      - version 乐观锁：防止取消订单后又被旧消息覆盖
--      - ON DUPLICATE KEY UPDATE 保证幂等
-- -------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `order_sync` (
    `id`           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `order_id`     BIGINT UNSIGNED NOT NULL COMMENT '订单ID（订单系统主键）',
    `user_id`      BIGINT UNSIGNED NOT NULL,
    `item_id`      BIGINT UNSIGNED NOT NULL,
    `category`     VARCHAR(64),
    `quantity`     INT             NOT NULL DEFAULT 1,
    `order_amount` DECIMAL(15,2)   NOT NULL COMMENT 'GMV口径: 含未支付金额',
    `paid_amount`  DECIMAL(15,2)            COMMENT '实付金额',
    -- 0待支付 1已支付 2已取消 3已退款
    `order_status` TINYINT         NOT NULL DEFAULT 0,
    `version`      BIGINT          NOT NULL DEFAULT 0 COMMENT '乐观锁版本号，越大越新',
    `order_time`   DATETIME        NOT NULL,
    `update_time`  DATETIME        NOT NULL,
    `synced_at`    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_order` (`order_id`),
    KEY `idx_user_time` (`user_id`, `order_time`),
    KEY `idx_item_time` (`item_id`, `order_time`),
    KEY `idx_status_time` (`order_status`, `order_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单同步表';

-- -------------------------------------------------------------
-- 4. 行为-订单关联临时表（双流 Join 等待区，面试稿 2.4）
--    用户行为数据和订单数据分别到达，需要等两边都到齐才写入分析
--    超过 1h 未匹配 → 定时任务触发补偿
-- -------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `join_temp_event` (
    `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `biz_key`       VARCHAR(128)    NOT NULL COMMENT '关联Key: user_id:item_id:session_id',
    -- 0: 等待中  1: 已匹配  2: 补偿中  3: 死信
    `status`        TINYINT         NOT NULL DEFAULT 0,
    `event_payload` JSON                     COMMENT '行为数据快照',
    `order_payload` JSON                     COMMENT '订单数据快照',
    `retry_cnt`     INT             NOT NULL DEFAULT 0,
    `created_at`    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `matched_at`    DATETIME,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_biz_key` (`biz_key`),
    KEY `idx_status_created` (`status`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='行为-订单双流Join临时表';

-- -------------------------------------------------------------
-- 5. ID-Mapping 表（OneID 方案，面试稿 1.7）
--    登录时双绑 device_id + user_id，数仓侧每日用 Spark 图计算归并
-- -------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `id_mapping` (
    `id`         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `device_id`  VARCHAR(64)     NOT NULL,
    `user_id`    BIGINT UNSIGNED NOT NULL,
    `bind_time`  DATETIME        NOT NULL COMMENT '登录绑定时间',
    `source`     VARCHAR(16)              COMMENT '来源: app/h5/mini',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_device_user` (`device_id`, `user_id`),
    KEY `idx_user` (`user_id`),
    KEY `idx_device` (`device_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='设备-用户ID映射表(OneID)';

-- -------------------------------------------------------------
-- 6. 商品基础信息表（商品目录，Canal 从商品系统同步到 ES/MySQL）
--    运营后台 TOP 榜单查询时 JOIN 此表获取商品名、品牌
-- -------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `product_info` (
    `item_id`    BIGINT UNSIGNED NOT NULL COMMENT '商品ID(商品系统主键)',
    `title`      VARCHAR(256)    NOT NULL COMMENT '商品标题',
    `category`   VARCHAR(64)              COMMENT '一级类目',
    `sub_category` VARCHAR(64)            COMMENT '二级类目',
    `brand`      VARCHAR(64)              COMMENT '品牌',
    `price`      DECIMAL(10,2)            COMMENT '当前售价',
    `cost_price` DECIMAL(10,2)            COMMENT '成本价(毛利分析)',
    `status`     TINYINT         NOT NULL DEFAULT 1 COMMENT '1:在售 0:下架',
    `sales30d`   BIGINT          NOT NULL DEFAULT 0 COMMENT '近30日销量(冗余字段,周期更新)',
    `stock`      INT             NOT NULL DEFAULT 0 COMMENT '库存(冗余,实时性要求高时走商品系统)',
    `created_at` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`item_id`),
    KEY `idx_category` (`category`, `status`),
    KEY `idx_brand`    (`brand`, `status`),
    KEY `idx_updated`  (`updated_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商品基础信息(Canal同步,供运营分析JOIN使用)';

-- -------------------------------------------------------------
-- 7. 类目每日聚合表（运营选品分析：各类目 PV/UV/GMV 对比）
--    凌晨聚合任务 DailyAggregateTask 填充
-- -------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `category_daily` (
    `id`              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `event_date`      DATE            NOT NULL COMMENT '统计日期',
    `category`        VARCHAR(64)     NOT NULL COMMENT '类目',
    `pv`              BIGINT          NOT NULL DEFAULT 0 COMMENT '浏览量',
    `uv`              BIGINT          NOT NULL DEFAULT 0 COMMENT '独立访客',
    `search_cnt`      BIGINT          NOT NULL DEFAULT 0 COMMENT '搜索次数',
    `add_cart_cnt`    BIGINT          NOT NULL DEFAULT 0 COMMENT '加购次数',
    `create_order_cnt` BIGINT         NOT NULL DEFAULT 0 COMMENT '创建订单次数',
    `pay_cnt`         BIGINT          NOT NULL DEFAULT 0 COMMENT '支付笔数',
    `pay_amount`      DECIMAL(15,2)   NOT NULL DEFAULT 0 COMMENT '实付金额',
    `gmv`             DECIMAL(15,2)   NOT NULL DEFAULT 0 COMMENT 'GMV(含未支付)',
    `item_cnt`        BIGINT          NOT NULL DEFAULT 0 COMMENT '有交易的商品SKU数',
    `updated_at`      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_date_category` (`event_date`, `category`),
    KEY `idx_category_date` (`category`, `event_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='类目每日聚合表(运营大盘类目分析)';

-- -------------------------------------------------------------
-- 8. 热搜词每日聚合（运营选品：发现趋势词、新品机会）
--    来源：event_detail WHERE event_name='search' AND ext_info.keyword IS NOT NULL
-- -------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `search_keyword_daily` (
    `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `event_date`  DATE            NOT NULL COMMENT '统计日期',
    `keyword`     VARCHAR(128)    NOT NULL COMMENT '搜索关键词',
    `search_cnt`  BIGINT          NOT NULL DEFAULT 0 COMMENT '搜索次数',
    `uv`          BIGINT          NOT NULL DEFAULT 0 COMMENT '搜索人数(device_id去重)',
    `click_cnt`   BIGINT          NOT NULL DEFAULT 0 COMMENT '点击商品次数(search后click_item事件)',
    `pay_cnt`     BIGINT          NOT NULL DEFAULT 0 COMMENT '搜索后支付次数',
    `pay_amount`  DECIMAL(15,2)   NOT NULL DEFAULT 0 COMMENT '搜索带来的成交金额',
    `ctr`         DECIMAL(5,4)    NOT NULL DEFAULT 0 COMMENT '点击率=click_cnt/search_cnt',
    `cvr`         DECIMAL(5,4)    NOT NULL DEFAULT 0 COMMENT '转化率=pay_cnt/uv',
    `updated_at`  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_date_keyword` (`event_date`, `keyword`),
    KEY `idx_date_search_cnt` (`event_date`, `search_cnt`)   -- 按热度排行
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='热搜词每日聚合(运营选品监控搜索趋势)';

-- -------------------------------------------------------------
-- 9. 商品排行榜（TOP 100，每天凌晨计算，不同维度各存一份）
--    rank_type: gmv / pay_cnt / pv / add_cart_cnt
-- -------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `item_ranking_daily` (
    `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `rank_date`   DATE            NOT NULL COMMENT '榜单日期',
    `rank_type`   VARCHAR(32)     NOT NULL COMMENT '榜单类型: gmv/pay_cnt/pv/add_cart_cnt',
    `category`    VARCHAR(64)              COMMENT '类目过滤(NULL表示全类目)',
    `rank_no`     SMALLINT        NOT NULL COMMENT '当日排名',
    `prev_rank_no` SMALLINT                COMMENT '昨日排名(NULL表示新上榜)',
    `item_id`     BIGINT UNSIGNED NOT NULL COMMENT '商品ID',
    `item_name`   VARCHAR(256)             COMMENT '商品名(冗余,避免JOIN)',
    `brand`       VARCHAR(64)              COMMENT '品牌(冗余)',
    `item_category` VARCHAR(64)            COMMENT '商品类目(冗余)',
    `pv`          BIGINT          NOT NULL DEFAULT 0,
    `uv`          BIGINT          NOT NULL DEFAULT 0,
    `add_cart_cnt` BIGINT         NOT NULL DEFAULT 0,
    `pay_cnt`     BIGINT          NOT NULL DEFAULT 0,
    `pay_amount`  DECIMAL(15,2)   NOT NULL DEFAULT 0 COMMENT '实付金额',
    `gmv`         DECIMAL(15,2)   NOT NULL DEFAULT 0,
    `updated_at`  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_date_type_rank` (`rank_date`, `rank_type`, `category`, `rank_no`),
    KEY `idx_date_type_item` (`rank_date`, `rank_type`, `item_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商品排行榜(每日TOP100,多维度,运营选品核心表)';

-- -------------------------------------------------------------
-- 10. 平台整体大盘指标（GMV/订单量/UV 趋势，每天凌晨汇总）
--     运营大盘首页、经营周报数据来源
-- -------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `platform_daily` (
    `id`              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `event_date`      DATE            NOT NULL COMMENT '统计日期',
    `pv`              BIGINT          NOT NULL DEFAULT 0 COMMENT '全站PV',
    `uv`              BIGINT          NOT NULL DEFAULT 0 COMMENT '全站UV(device_id去重)',
    `new_user_cnt`    BIGINT          NOT NULL DEFAULT 0 COMMENT '新用户数(首次出现的device_id)',
    `search_cnt`      BIGINT          NOT NULL DEFAULT 0 COMMENT '搜索次数',
    `add_cart_cnt`    BIGINT          NOT NULL DEFAULT 0 COMMENT '加购次数',
    `create_order_cnt` BIGINT         NOT NULL DEFAULT 0 COMMENT '创建订单次数',
    `pay_order_cnt`   BIGINT          NOT NULL DEFAULT 0 COMMENT '支付成功订单数',
    `pay_user_cnt`    BIGINT          NOT NULL DEFAULT 0 COMMENT '支付人数(user_id去重)',
    `pay_amount`      DECIMAL(15,2)   NOT NULL DEFAULT 0 COMMENT '实付总金额',
    `gmv`             DECIMAL(15,2)   NOT NULL DEFAULT 0 COMMENT 'GMV(含未支付)',
    `refund_cnt`      BIGINT          NOT NULL DEFAULT 0 COMMENT '退款单数',
    `refund_amount`   DECIMAL(15,2)   NOT NULL DEFAULT 0 COMMENT '退款金额',
    -- 转化指标
    `cart_rate`       DECIMAL(5,4)    NOT NULL DEFAULT 0 COMMENT '加购率=add_cart_uv/uv',
    `order_rate`      DECIMAL(5,4)    NOT NULL DEFAULT 0 COMMENT '下单率=create_order_uv/uv',
    `pay_rate`        DECIMAL(5,4)    NOT NULL DEFAULT 0 COMMENT '支付转化率=pay_user_cnt/uv',
    `arpu`            DECIMAL(10,2)   NOT NULL DEFAULT 0 COMMENT '每用户平均收入=pay_amount/pay_user_cnt',
    `updated_at`      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_event_date` (`event_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='平台每日整体大盘指标(运营首页核心数据)';

-- -------------------------------------------------------------
-- 11.x 订单事务消息本地日志表（OrderTxProducer 用，面试稿 2.5 加强版）
--      用于 RocketMQ 事务消息回查
-- -------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `order_local_log` (
    `id`         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `order_id`   BIGINT UNSIGNED NOT NULL COMMENT '订单 ID',
    `status`     VARCHAR(16)     NOT NULL DEFAULT 'SENT' COMMENT 'SENT / DONE',
    `created_at` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_order_id` (`order_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='事务消息本地日志(用于 checkLocalTransaction 反查)';

-- -------------------------------------------------------------
-- 11. 每日全量校验报告（面试稿 2.9 数据对账）
-- -------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `data_verify_report` (
    `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `verify_date`   DATE            NOT NULL,
    `source`        VARCHAR(32)     NOT NULL COMMENT '校验来源: user_event/order_sync',
    `expect_cnt`    BIGINT          NOT NULL COMMENT '来源系统记录数',
    `actual_cnt`    BIGINT          NOT NULL COMMENT '本系统记录数',
    `diff_cnt`      BIGINT          NOT NULL COMMENT '差异条数(负数=本系统少)',
    `status`        TINYINT         NOT NULL DEFAULT 0 COMMENT '0:有差异 1:一致',
    `fix_status`    TINYINT         NOT NULL DEFAULT 0 COMMENT '0:未修复 1:已修复',
    `detail`        TEXT                     COMMENT '差异明细摘要',
    `created_at`    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_date_source` (`verify_date`, `source`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='每日数据对账报告';
