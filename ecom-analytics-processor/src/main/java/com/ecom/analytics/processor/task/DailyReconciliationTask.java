package com.ecom.analytics.processor.task;

import com.ecom.analytics.common.alert.AlertLevel;
import com.ecom.analytics.common.alert.AlertService;
import com.ecom.analytics.common.util.MonthlyTableUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.Date;
import java.time.LocalDate;
import java.util.Locale;

/**
 * 每日凌晨全量校验任务 (面试稿 2.2 / 2.4 / 2.9)
 *
 * <h3>目标</h3>
 * 凌晨 01:30 跑 (在 DailyAggregateTask 00:30 之后), 自动对账三个数据源,
 * 把差异写入 data_verify_report 表, 差异超阈值通过 AlertService 飞书告警。
 *
 * <h3>三个对账维度</h3>
 * <ol>
 *   <li><b>mysql_vs_clickhouse</b>: MySQL event_detail_YYYYMM 当日条数 vs ClickHouse events_local 当日条数。
 *       揭示双写丢失 (CK 写失败被吞掉) 这类问题。</li>
 *   <li><b>order_sync_vs_local_log</b>: order_sync 当日订单数 vs order_local_log 当日事务日志数。
 *       揭示事务消息发出但本地事务回滚 / 消息丢失这类问题。</li>
 *   <li><b>join_temp_dead</b>: join_temp_event 中 status=3 (死信) 的累计条数。
 *       任何死信都视为对账失败, 需人工介入。</li>
 * </ol>
 *
 * <h3>差异阈值</h3>
 *  - mysql_vs_clickhouse 差异 > 1% → ERROR 告警
 *  - order_sync_vs_local_log 差异 > 0 → CRITICAL (订单数据不容差)
 *  - join_temp_dead > 0 → CRITICAL
 *
 * <h3>幂等</h3>
 * 同一 (verify_date, source) 的报告每天最多一条; INSERT 用 ON DUPLICATE KEY UPDATE 覆盖。
 * 任务重跑不会重复写, 历史报告留痕 (data_verify_report 表 KEY idx_date_source)。
 */
@Slf4j
@Component
public class DailyReconciliationTask {

    /** mysql_vs_clickhouse 容差: 1% 内视为一致 */
    private static final double CK_TOLERANCE = 0.01;

    private final JdbcTemplate mysqlJdbcTemplate;
    private final JdbcTemplate ckJdbcTemplate;
    private final AlertService alertService;

    public DailyReconciliationTask(JdbcTemplate mysqlJdbcTemplate,
                                   @Qualifier("clickHouseJdbcTemplate") JdbcTemplate ckJdbcTemplate,
                                   AlertService alertService) {
        this.mysqlJdbcTemplate = mysqlJdbcTemplate;
        this.ckJdbcTemplate = ckJdbcTemplate;
        this.alertService = alertService;
    }

    /**
     * 每天 01:30 执行。在 DailyAggregateTask (00:30) 之后, 给聚合 60 分钟窗口跑完。
     */
    @Scheduled(cron = "0 30 1 * * ?")
    public void run() {
        LocalDate date = LocalDate.now().minusDays(1);
        log.info("=== DailyReconciliation start, date={} ===", date);

        try { reconcileMysqlVsClickhouse(date); } catch (Exception e) { logAndAlert("mysql_vs_clickhouse", date, e); }
        try { reconcileOrderVsLocalLog(date); }   catch (Exception e) { logAndAlert("order_sync_vs_local_log", date, e); }
        try { reconcileJoinTempDead(date); }      catch (Exception e) { logAndAlert("join_temp_dead", date, e); }

        log.info("=== DailyReconciliation done, date={} ===", date);
    }

    // ─────────────────────────────────────────────────────────────────
    // 维度 1: MySQL event_detail vs ClickHouse events_local
    // ─────────────────────────────────────────────────────────────────

    private void reconcileMysqlVsClickhouse(LocalDate date) {
        String table = MonthlyTableUtil.tableOf(date.atStartOfDay());
        Long mysqlCnt = mysqlJdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM " + table + " WHERE DATE(ts) = ?",
                Long.class, Date.valueOf(date));
        Long ckCnt = ckJdbcTemplate.queryForObject(
                "SELECT count() FROM ecom_analytics.events_local WHERE event_date = ?",
                Long.class, Date.valueOf(date));

        mysqlCnt = mysqlCnt == null ? 0 : mysqlCnt;
        ckCnt = ckCnt == null ? 0 : ckCnt;
        long diff = mysqlCnt - ckCnt;
        double diffRatio = mysqlCnt == 0 ? 0.0 : Math.abs(diff) / (double) mysqlCnt;
        int status = diffRatio <= CK_TOLERANCE ? 1 : 0;

        String detail = String.format(Locale.ROOT,
                "MySQL=%d CK=%d diff=%d ratio=%.4f%%", mysqlCnt, ckCnt, diff, diffRatio * 100);
        writeReport(date, "mysql_vs_clickhouse", mysqlCnt, ckCnt, diff, status, detail);

        if (status == 0) {
            alertService.send(AlertLevel.ERROR,
                    "[对账]MySQL vs ClickHouse 差异超阈值",
                    "**日期**: " + date + "\n" +
                    "**详情**: " + detail + "\n" +
                    "**建议**: 排查 EventPersistService 双写是否有 CK 写入静默失败");
        }
        log.info("[Reconcil] mysql_vs_clickhouse {}", detail);
    }

    // ─────────────────────────────────────────────────────────────────
    // 维度 2: order_sync vs order_local_log (事务消息一致性)
    // ─────────────────────────────────────────────────────────────────

    private void reconcileOrderVsLocalLog(LocalDate date) {
        Long syncCnt = mysqlJdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM order_sync WHERE DATE(order_time) = ?",
                Long.class, Date.valueOf(date));
        Long logCnt = mysqlJdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM order_local_log WHERE DATE(created_at) = ?",
                Long.class, Date.valueOf(date));

        syncCnt = syncCnt == null ? 0 : syncCnt;
        logCnt = logCnt == null ? 0 : logCnt;
        long diff = syncCnt - logCnt;
        int status = diff == 0 ? 1 : 0;

        String detail = String.format(Locale.ROOT,
                "order_sync=%d order_local_log=%d diff=%d", syncCnt, logCnt, diff);
        writeReport(date, "order_sync_vs_local_log", syncCnt, logCnt, diff, status, detail);

        if (status == 0) {
            alertService.send(AlertLevel.CRITICAL,
                    "[对账]订单 sync 与本地事务日志不一致",
                    "**日期**: " + date + "\n" +
                    "**详情**: " + detail + "\n" +
                    "**建议**: 排查 OrderTxProducer 事务消息回查逻辑, 或 collector→processor 链路丢消息");
        }
        log.info("[Reconcil] order_sync_vs_local_log {}", detail);
    }

    // ─────────────────────────────────────────────────────────────────
    // 维度 3: join_temp_event 死信存量
    // ─────────────────────────────────────────────────────────────────

    private void reconcileJoinTempDead(LocalDate date) {
        Long deadCnt = mysqlJdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM join_temp_event WHERE status = 3 AND DATE(created_at) = ?",
                Long.class, Date.valueOf(date));
        deadCnt = deadCnt == null ? 0 : deadCnt;
        int status = deadCnt == 0 ? 1 : 0;
        String detail = "dead_letter_cnt=" + deadCnt;
        writeReport(date, "join_temp_dead", 0, deadCnt, -deadCnt, status, detail);

        if (status == 0) {
            alertService.send(AlertLevel.CRITICAL,
                    "[对账]双流 Join 死信存量",
                    "**日期**: " + date + "\n" +
                    "**死信条数**: " + deadCnt + "\n" +
                    "**建议**: 查 join_temp_event WHERE status=3 取 biz_key 列表, 手工补数据 or 评估弃单");
        }
        log.info("[Reconcil] join_temp_dead {}", detail);
    }

    // ─────────────────────────────────────────────────────────────────
    // 内部工具
    // ─────────────────────────────────────────────────────────────────

    private void writeReport(LocalDate date, String source,
                             long expect, long actual, long diff,
                             int status, String detail) {
        mysqlJdbcTemplate.update(
                "INSERT INTO data_verify_report " +
                "  (verify_date, source, expect_cnt, actual_cnt, diff_cnt, status, fix_status, detail) " +
                " VALUES (?, ?, ?, ?, ?, ?, 0, ?) " +
                " ON DUPLICATE KEY UPDATE" +
                "   expect_cnt = VALUES(expect_cnt), actual_cnt = VALUES(actual_cnt)," +
                "   diff_cnt   = VALUES(diff_cnt),   status     = VALUES(status)," +
                "   detail     = VALUES(detail)",
                Date.valueOf(date), source, expect, actual, diff, status, detail);
    }

    private void logAndAlert(String source, LocalDate date, Exception e) {
        log.error("[Reconcil] source={} date={} fail", source, date, e);
        alertService.send(AlertLevel.ERROR,
                "[对账]任务执行异常",
                "**source**: " + source + "\n" +
                "**date**: " + date + "\n" +
                "**异常**: " + e.getMessage());
    }
}
