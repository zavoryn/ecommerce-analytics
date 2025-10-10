package com.ecom.analytics.processor.task;

import com.ecom.analytics.common.alert.AlertLevel;
import com.ecom.analytics.common.alert.AlertService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.Date;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 凌晨预聚合任务（面试稿 2.7 性能优化核心）
 *
 * 每天 00:30 执行，将前一天数据汇总到多张聚合表，
 * 运营大盘所有查询走聚合表，响应时间从秒级 → 100ms 以内。
 *
 * 五步流水线（按依赖顺序）：
 *  Step 1: event_agg_daily       ← event_detail_YYYYMM（商品维度明细聚合）
 *  Step 2: category_daily        ← event_detail_YYYYMM + order_sync（类目维度聚合）
 *  Step 3: search_keyword_daily  ← event_detail_YYYYMM WHERE event_name='search'
 *  Step 4: item_ranking_daily    ← event_agg_daily + order_sync + product_info（TOP 榜计算）
 *  Step 5: platform_daily        ← event_agg_daily + order_sync（平台整体大盘）
 *
 * 失败兜底：最多重试 3 次，仍失败则打 ERROR 告警日志（生产接入飞书/短信）。
 * 幂等保证：所有 INSERT 使用 ON DUPLICATE KEY UPDATE，重复执行不影响结果。
 *
 * 注意：需要 mysql + clickhouse 双数据源，使用显式构造器 + @Qualifier。
 */
@Slf4j
@Component
public class DailyAggregateTask {

    private static final int MAX_RETRY = 3;
    /** 每个榜单最多保留 TOP N 条 */
    private static final int RANKING_TOP_N = 100;

    private final JdbcTemplate mysqlJdbcTemplate;
    private final JdbcTemplate ckJdbcTemplate;
    private final AlertService alertService;

    public DailyAggregateTask(JdbcTemplate mysqlJdbcTemplate,
                              @Qualifier("clickHouseJdbcTemplate") JdbcTemplate ckJdbcTemplate,
                              AlertService alertService) {
        this.mysqlJdbcTemplate = mysqlJdbcTemplate;
        this.ckJdbcTemplate = ckJdbcTemplate;
        this.alertService = alertService;
    }

    @Scheduled(cron = "0 30 0 * * ?")   // 每天 00:30 执行
    public void run() {
        LocalDate yesterday = LocalDate.now().minusDays(1);
        log.info("=== DailyAggregateTask start, date={} ===", yesterday);

        runWithRetry("Step1-EventAggDaily",    () -> aggregateEventAggDaily(yesterday));
        runWithRetry("Step2-CategoryDaily",    () -> aggregateCategoryDaily(yesterday));
        runWithRetry("Step3-SearchKeyword",    () -> aggregateSearchKeyword(yesterday));
        runWithRetry("Step4-ItemRanking",      () -> aggregateItemRanking(yesterday));
        runWithRetry("Step5-PlatformDaily",    () -> aggregatePlatformDaily(yesterday));

        log.info("=== DailyAggregateTask done, date={} ===", yesterday);
    }

    // ═══════════════════════════════════════════════════
    //  Step 1: 商品维度每日聚合 → event_agg_daily
    // ═══════════════════════════════════════════════════

    /**
     * 将 event_detail_YYYYMM 按 (item_id, event_date) 汇总到聚合表。
     * JOIN order_sync 补充 pay_amount（实付金额）。
     */
    private void aggregateEventAggDaily(LocalDate date) {
        String detailTable = "event_detail_" + yyyyMM(date);
        mysqlJdbcTemplate.update(
                "INSERT INTO event_agg_daily " +
                "  (event_date, item_id, category, brand, pv, uv, search_cnt, " +
                "   add_cart_cnt, create_order_cnt, pay_cnt, pay_amount, gmv, updated_at) " +
                "SELECT" +
                "  ? AS event_date," +
                "  CAST(JSON_UNQUOTE(JSON_EXTRACT(e.ext_info, '$.item_id')) AS UNSIGNED) AS item_id," +
                "  JSON_UNQUOTE(JSON_EXTRACT(e.ext_info, '$.category'))    AS category," +
                "  COALESCE(p.brand, '')                                    AS brand," +
                "  COUNT(1)                                                  AS pv," +
                "  COUNT(DISTINCT e.device_id)                               AS uv," +
                "  SUM(CASE WHEN e.event_name = 'search'       THEN 1 ELSE 0 END) AS search_cnt," +
                "  SUM(CASE WHEN e.event_name = 'add_cart'     THEN 1 ELSE 0 END) AS add_cart_cnt," +
                "  SUM(CASE WHEN e.event_name = 'create_order' THEN 1 ELSE 0 END) AS create_order_cnt," +
                "  SUM(CASE WHEN e.event_name = 'pay_order'    THEN 1 ELSE 0 END) AS pay_cnt," +
                "  COALESCE(SUM(o.paid_amount), 0)                          AS pay_amount," +
                "  COALESCE(SUM(o.order_amount), 0)                         AS gmv," +
                "  NOW()                                                     AS updated_at" +
                " FROM " + detailTable + " e" +
                " LEFT JOIN product_info p" +
                "   ON CAST(JSON_UNQUOTE(JSON_EXTRACT(e.ext_info, '$.item_id')) AS UNSIGNED) = p.item_id" +
                " LEFT JOIN order_sync o" +
                "   ON o.item_id = CAST(JSON_UNQUOTE(JSON_EXTRACT(e.ext_info, '$.item_id')) AS UNSIGNED)" +
                "   AND DATE(o.order_time) = ? AND o.order_status = 1" +
                " WHERE DATE(e.ts) = ?" +
                "   AND JSON_EXTRACT(e.ext_info, '$.item_id') IS NOT NULL" +
                " GROUP BY item_id, category, brand" +
                " ON DUPLICATE KEY UPDATE" +
                "   pv = VALUES(pv), uv = VALUES(uv), search_cnt = VALUES(search_cnt)," +
                "   add_cart_cnt = VALUES(add_cart_cnt), create_order_cnt = VALUES(create_order_cnt)," +
                "   pay_cnt = VALUES(pay_cnt), pay_amount = VALUES(pay_amount)," +
                "   gmv = VALUES(gmv), category = VALUES(category), brand = VALUES(brand)," +
                "   updated_at = NOW()",
                Date.valueOf(date), Date.valueOf(date), Date.valueOf(date));
        log.info("Step1 done: event_agg_daily date={}", date);
    }

    // ═══════════════════════════════════════════════════
    //  Step 2: 类目维度聚合 → category_daily
    // ═══════════════════════════════════════════════════

    /**
     * 按类目汇总 PV/UV/GMV，join order_sync 获取实付金额。
     * category 来自 event_detail.ext_info.category 或 product_info.category。
     */
    private void aggregateCategoryDaily(LocalDate date) {
        // 直接从 event_agg_daily（已聚合完毕的 Step1 结果）按类目 GROUP BY
        mysqlJdbcTemplate.update(
                "INSERT INTO category_daily " +
                "  (event_date, category, pv, uv, search_cnt, add_cart_cnt, " +
                "   create_order_cnt, pay_cnt, pay_amount, gmv, item_cnt, updated_at) " +
                "SELECT event_date, category," +
                "  SUM(pv), SUM(uv), SUM(search_cnt), SUM(add_cart_cnt)," +
                "  SUM(create_order_cnt), SUM(pay_cnt), SUM(pay_amount), SUM(gmv)," +
                "  COUNT(DISTINCT item_id), NOW()" +
                " FROM event_agg_daily" +
                " WHERE event_date = ? AND category IS NOT NULL AND category != ''" +
                " GROUP BY event_date, category" +
                " ON DUPLICATE KEY UPDATE" +
                "   pv = VALUES(pv), uv = VALUES(uv), search_cnt = VALUES(search_cnt)," +
                "   add_cart_cnt = VALUES(add_cart_cnt), create_order_cnt = VALUES(create_order_cnt)," +
                "   pay_cnt = VALUES(pay_cnt), pay_amount = VALUES(pay_amount)," +
                "   gmv = VALUES(gmv), item_cnt = VALUES(item_cnt), updated_at = NOW()",
                Date.valueOf(date));
        log.info("Step2 done: category_daily date={}", date);
    }

    // ═══════════════════════════════════════════════════
    //  Step 3: 热搜词聚合 → search_keyword_daily
    // ═══════════════════════════════════════════════════

    /**
     * 从明细表中提取搜索事件的关键词，统计搜索量、点击量、转化量。
     * keyword 来自 ext_info.keyword（埋点时前端上报）。
     * click_cnt / pay_cnt 用 session_id 关联同会话后续事件（简化：同 device_id 当日后续事件）。
     */
    private void aggregateSearchKeyword(LocalDate date) {
        String detailTable = "event_detail_" + yyyyMM(date);
        mysqlJdbcTemplate.update(
                "INSERT INTO search_keyword_daily " +
                "  (event_date, keyword, search_cnt, uv, click_cnt, pay_cnt, " +
                "   pay_amount, ctr, cvr, updated_at) " +
                "SELECT" +
                "  ? AS event_date," +
                "  JSON_UNQUOTE(JSON_EXTRACT(s.ext_info, '$.keyword')) AS keyword," +
                "  COUNT(1)                AS search_cnt," +
                "  COUNT(DISTINCT s.device_id) AS uv," +
                // click_cnt: 同device_id当日有click事件（且在搜索后，简化为当日出现）
                "  COUNT(DISTINCT c.id)   AS click_cnt," +
                // pay_cnt: 同device_id当日有pay_order事件
                "  COUNT(DISTINCT py.id)  AS pay_cnt," +
                "  COALESCE(SUM(o.paid_amount), 0) AS pay_amount," +
                "  IF(COUNT(1) > 0, COUNT(DISTINCT c.id) / COUNT(1), 0) AS ctr," +
                "  IF(COUNT(DISTINCT s.device_id) > 0, COUNT(DISTINCT py.id) / COUNT(DISTINCT s.device_id), 0) AS cvr," +
                "  NOW()" +
                " FROM " + detailTable + " s" +
                // 关联同设备当日的点击事件
                " LEFT JOIN " + detailTable + " c" +
                "   ON c.device_id = s.device_id AND DATE(c.ts) = DATE(s.ts)" +
                "   AND c.event_name = 'click' AND c.ts >= s.ts" +
                // 关联同设备当日的支付事件
                " LEFT JOIN " + detailTable + " py" +
                "   ON py.device_id = s.device_id AND DATE(py.ts) = DATE(s.ts)" +
                "   AND py.event_name = 'pay_order'" +
                // 关联订单实付金额
                " LEFT JOIN order_sync o" +
                "   ON o.user_id = s.user_id AND DATE(o.order_time) = ?" +
                "   AND o.order_status = 1" +
                " WHERE s.event_name = 'search' AND DATE(s.ts) = ?" +
                "   AND JSON_EXTRACT(s.ext_info, '$.keyword') IS NOT NULL" +
                "   AND JSON_UNQUOTE(JSON_EXTRACT(s.ext_info, '$.keyword')) != ''" +
                " GROUP BY keyword" +
                " ON DUPLICATE KEY UPDATE" +
                "   search_cnt = VALUES(search_cnt), uv = VALUES(uv)," +
                "   click_cnt = VALUES(click_cnt), pay_cnt = VALUES(pay_cnt)," +
                "   pay_amount = VALUES(pay_amount), ctr = VALUES(ctr), cvr = VALUES(cvr)," +
                "   updated_at = NOW()",
                Date.valueOf(date), Date.valueOf(date), Date.valueOf(date));
        log.info("Step3 done: search_keyword_daily date={}", date);
    }

    // ═══════════════════════════════════════════════════
    //  Step 4: 商品排行榜计算 → item_ranking_daily
    // ═══════════════════════════════════════════════════

    /**
     * 计算当日各榜单 TOP N。
     * 4 个榜单（gmv / pay_cnt / pv / add_cart_cnt）× 2 维度（全类目 / 分类目），共 5 套。
     * rank_no 1~N，与昨日 item_ranking_daily 对比生成 prev_rank_no。
     */
    private void aggregateItemRanking(LocalDate date) {
        // 先删当日旧数据（防重复运行）
        mysqlJdbcTemplate.update(
                "DELETE FROM item_ranking_daily WHERE rank_date = ?", Date.valueOf(date));

        for (String rankType : new String[]{"gmv", "pay_cnt", "pv", "add_cart_cnt"}) {
            // 全类目榜
            insertRankingBatch(date, rankType, null);
            // 分类目榜（按 category 分组，各取 TOP N）
            List<String> categories = mysqlJdbcTemplate.queryForList(
                    "SELECT DISTINCT category FROM category_daily WHERE event_date = ? AND category IS NOT NULL",
                    String.class, Date.valueOf(date));
            for (String cat : categories) {
                insertRankingBatch(date, rankType, cat);
            }
        }
        log.info("Step4 done: item_ranking_daily date={}", date);
    }

    private void insertRankingBatch(LocalDate date, String rankType, String category) {
        LocalDate yesterday = date.minusDays(1);

        // rankType 来自上层固定数组(gmv/pay_cnt/pv/add_cart_cnt),不接受外部输入,可安全拼接为列名
        String valueCol = switch (rankType) {
            case "pay_cnt"      -> "SUM(a.pay_cnt)";
            case "pv"           -> "SUM(a.pv)";
            case "add_cart_cnt" -> "SUM(a.add_cart_cnt)";
            default             -> "SUM(a.pay_amount)";  // gmv
        };

        // category 来自外部(运营查询参数), 必须全参数化避免 SQL 注入
        // 用 (? IS NULL OR col = ?) 模式同时支持"全类目(null)"与"指定类目"
        mysqlJdbcTemplate.update(
                "INSERT INTO item_ranking_daily " +
                "  (rank_date, rank_type, category, rank_no, prev_rank_no, item_id, item_name," +
                "   brand, item_category, pv, uv, add_cart_cnt, pay_cnt, pay_amount, gmv, updated_at) " +
                "SELECT" +
                "  ? AS rank_date, ? AS rank_type, ? AS category," +
                "  ROW_NUMBER() OVER (ORDER BY " + valueCol + " DESC) AS rank_no," +
                "  prev.rank_no AS prev_rank_no," +
                "  a.item_id," +
                "  COALESCE(p.title, CONCAT('商品-', a.item_id)) AS item_name," +
                "  COALESCE(p.brand, '')   AS brand," +
                "  a.category              AS item_category," +
                "  SUM(a.pv) AS pv, SUM(a.uv) AS uv," +
                "  SUM(a.add_cart_cnt) AS add_cart_cnt, SUM(a.pay_cnt) AS pay_cnt," +
                "  SUM(a.pay_amount) AS pay_amount, SUM(a.gmv) AS gmv, NOW()" +
                " FROM event_agg_daily a" +
                " LEFT JOIN product_info p ON a.item_id = p.item_id" +
                " LEFT JOIN item_ranking_daily prev" +
                "   ON prev.rank_date = ? AND prev.rank_type = ? AND prev.item_id = a.item_id" +
                "   AND ((? IS NULL AND prev.category IS NULL) OR prev.category = ?)" +
                " WHERE a.event_date = ?" +
                "   AND (? IS NULL OR a.category = ?)" +
                " GROUP BY a.item_id, p.title, p.brand, a.category, prev.rank_no" +
                " ORDER BY " + valueCol + " DESC" +
                " LIMIT ?",
                Date.valueOf(date), rankType, category,                  // SELECT 三个参数
                Date.valueOf(yesterday), rankType, category, category,   // JOIN ON 四个参数
                Date.valueOf(date), category, category,                  // WHERE 三个参数
                RANKING_TOP_N);
    }

    // ═══════════════════════════════════════════════════
    //  Step 5: 平台整体大盘 → platform_daily
    // ═══════════════════════════════════════════════════

    /**
     * 汇总全平台 PV/UV/GMV 等核心指标到 platform_daily。
     * UV 由 event_agg_daily SUM(uv) 近似（商品维度 uv 求和会有重复计数，此处为近似值）；
     * 精确 UV 生产中从 ClickHouse events_local 取 uniq(device_id)。
     */
    private void aggregatePlatformDaily(LocalDate date) {
        // 1) 行为指标来源：event_agg_daily 汇总（近似 UV，生产用 CK 精确去重替换）
        mysqlJdbcTemplate.update(
                "INSERT INTO platform_daily" +
                "  (event_date, pv, uv, search_cnt, add_cart_cnt, create_order_cnt," +
                "   pay_order_cnt, pay_user_cnt, pay_amount, gmv, refund_cnt, refund_amount," +
                "   cart_rate, order_rate, pay_rate, arpu, updated_at)" +
                " SELECT" +
                "  ? AS event_date," +
                "  COALESCE(SUM(a.pv), 0)               AS pv," +
                "  COALESCE(SUM(a.uv), 0)               AS uv," +   // 近似：商品级 uv 累加
                "  COALESCE(SUM(a.search_cnt), 0)       AS search_cnt," +
                "  COALESCE(SUM(a.add_cart_cnt), 0)     AS add_cart_cnt," +
                "  COALESCE(SUM(a.create_order_cnt), 0) AS create_order_cnt," +
                "  COALESCE((SELECT COUNT(*) FROM order_sync" +
                "             WHERE order_status=1 AND DATE(order_time)=?), 0)     AS pay_order_cnt," +
                "  COALESCE((SELECT COUNT(DISTINCT user_id) FROM order_sync" +
                "             WHERE order_status=1 AND DATE(order_time)=?), 0)     AS pay_user_cnt," +
                "  COALESCE((SELECT SUM(paid_amount) FROM order_sync" +
                "             WHERE order_status=1 AND DATE(order_time)=?), 0)     AS pay_amount," +
                "  COALESCE(SUM(a.gmv), 0)              AS gmv," +
                "  COALESCE((SELECT COUNT(*) FROM order_sync" +
                "             WHERE order_status=3 AND DATE(order_time)=?), 0)     AS refund_cnt," +
                "  COALESCE((SELECT SUM(paid_amount) FROM order_sync" +
                "             WHERE order_status=3 AND DATE(order_time)=?), 0)     AS refund_amount," +
                "  0 AS cart_rate, 0 AS order_rate, 0 AS pay_rate, 0 AS arpu, NOW()" +
                " FROM event_agg_daily a" +
                " WHERE a.event_date = ?" +
                " ON DUPLICATE KEY UPDATE" +
                "   pv = VALUES(pv), uv = VALUES(uv), search_cnt = VALUES(search_cnt)," +
                "   add_cart_cnt = VALUES(add_cart_cnt), create_order_cnt = VALUES(create_order_cnt)," +
                "   pay_order_cnt = VALUES(pay_order_cnt), pay_user_cnt = VALUES(pay_user_cnt)," +
                "   pay_amount = VALUES(pay_amount), gmv = VALUES(gmv)," +
                "   refund_cnt = VALUES(refund_cnt), refund_amount = VALUES(refund_amount)," +
                "   updated_at = NOW()",
                Date.valueOf(date),
                Date.valueOf(date), Date.valueOf(date), Date.valueOf(date),
                Date.valueOf(date), Date.valueOf(date),
                Date.valueOf(date));

        // 2) 补充计算转化率字段 + 新用户数(UPDATE 而非 INSERT, 依赖刚写入的数值)
        //
        //  new_user_cnt 口径: 当天在 id_mapping 首次出现 bind 记录的 user_id 数
        //    内层 SELECT user_id FROM id_mapping GROUP BY user_id HAVING MIN(bind_date) = ?
        //    → 取每个用户的首次绑定日期, HAVING 过滤当日新用户
        //    外层 COUNT(*) 即为当日新用户数
        //
        //  生产规模 id_mapping > 1000w 时此 SQL 会慢, 后续迁到 ClickHouse uniqIf 或离线计算。
        mysqlJdbcTemplate.update(
                "UPDATE platform_daily SET" +
                "  cart_rate  = IF(uv > 0, add_cart_cnt / uv, 0)," +
                "  order_rate = IF(uv > 0, create_order_cnt / uv, 0)," +
                "  pay_rate   = IF(uv > 0, pay_user_cnt / uv, 0)," +
                "  arpu       = IF(pay_user_cnt > 0, pay_amount / pay_user_cnt, 0)," +
                "  new_user_cnt = COALESCE((" +
                "    SELECT COUNT(*) FROM (" +
                "      SELECT user_id FROM id_mapping" +
                "       GROUP BY user_id HAVING MIN(DATE(bind_time)) = ?" +
                "    ) t" +
                "  ), 0)" +
                " WHERE event_date = ?",
                Date.valueOf(date), Date.valueOf(date));

        log.info("Step5 done: platform_daily date={}", date);
    }

    // ─── 工具方法 ──────────────────────────────────────

    /** 带重试的 step 执行 */
    private void runWithRetry(String stepName, Runnable step) {
        for (int attempt = 1; attempt <= MAX_RETRY; attempt++) {
            try {
                step.run();
                return;
            } catch (Exception e) {
                log.error("{} failed, attempt={}/{}", stepName, attempt, MAX_RETRY, e);
                if (attempt == MAX_RETRY) {
                    // 重试耗尽 → 飞书机器人告警, 触发人工介入
                    alertService.send(AlertLevel.CRITICAL,
                            "聚合任务失败-" + stepName,
                            "**任务**: `" + stepName + "`\n" +
                            "**重试次数**: " + MAX_RETRY + "\n" +
                            "**异常类**: `" + e.getClass().getSimpleName() + "`\n" +
                            "**异常信息**: `" + e.getMessage() + "`\n" +
                            "**处理建议**: 检查 MySQL / ClickHouse 连通性 + 表结构 + 数据量");
                }
            }
        }
    }

    private static final DateTimeFormatter YYYYMM = DateTimeFormatter.ofPattern("yyyyMM");

    /** 日期转 YYYYMM 字符串, 用于分表路由 */
    private String yyyyMM(LocalDate date) {
        return date.format(YYYYMM);
    }
}
