package com.ecom.analytics.query.service;

import com.ecom.analytics.query.dto.CategoryStatVO;
import com.ecom.analytics.query.dto.GmvOverviewVO;
import com.ecom.analytics.query.dto.GmvTrendPointVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 运营大盘服务
 *
 * 面向运营人员提供三大核心数据场景（面试稿 2.7 / 2.10）：
 *
 * 1. GMV 概览（overview）：运营首页核心指标卡片
 *    - 今日实时 GMV、订单数、UV、转化率
 *    - 日环比（今 vs 昨）、本月累计
 *    - 数据来源：order_sync（精确口径）+ ClickHouse events_local（UV实时）
 *
 * 2. GMV 趋势（gmvTrend）：折线图，判断业务走势
 *    - 优先走 platform_daily 聚合表（已有聚合结果时 < 10ms）
 *    - 降级：直接聚合 order_sync（当聚合表数据缺失时）
 *    - Redis 缓存 30min，防运营高并发同时刷新
 *
 * 3. 类目统计（categoryStats）：各类目 PV/UV/GMV 对比
 *    - 走 category_daily 聚合表
 *    - 计算 GMV 占比、加购率、支付转化率
 *
 * 注意：双数据源注入，必须用显式构造器 + @Qualifier，不可混用 @RequiredArgsConstructor。
 */
@Slf4j
@Service
public class OperationService {

    private final JdbcTemplate mysqlJdbcTemplate;
    private final JdbcTemplate ckJdbcTemplate;

    public OperationService(JdbcTemplate mysqlJdbcTemplate,
                            @Qualifier("clickHouseJdbcTemplate") JdbcTemplate ckJdbcTemplate) {
        this.mysqlJdbcTemplate = mysqlJdbcTemplate;
        this.ckJdbcTemplate = ckJdbcTemplate;
    }

    // ═══════════════════════════════════════════════════
    //  1. GMV 大盘概览（实时，不走缓存，运营每次刷新都取最新）
    // ═══════════════════════════════════════════════════

    /**
     * 平台 GMV 大盘概览
     *
     * 设计：今日数据需实时（不缓存），历史对比走聚合表。
     * order_sync 按 order_status=1(已支付) 过滤，paid_amount 为实付口径。
     */
    public GmvOverviewVO overview() {
        // 1) 从 order_sync 查今日、昨日、本月 GMV/订单数（一次扫描，减少 round-trip）
        GmvOverviewVO vo = mysqlJdbcTemplate.queryForObject(
                "SELECT " +
                "  COALESCE(SUM(CASE WHEN DATE(order_time) = CURDATE() THEN paid_amount END), 0) AS today_gmv, " +
                "  COALESCE(COUNT(CASE WHEN DATE(order_time) = CURDATE() THEN 1 END), 0) AS today_order_cnt, " +
                "  COALESCE(COUNT(DISTINCT CASE WHEN DATE(order_time) = CURDATE() THEN user_id END), 0) AS today_pay_user_cnt, " +
                "  COALESCE(SUM(CASE WHEN DATE(order_time) = DATE_SUB(CURDATE(), INTERVAL 1 DAY) THEN paid_amount END), 0) AS yesterday_gmv, " +
                "  COALESCE(SUM(CASE WHEN order_time >= DATE_FORMAT(NOW(), '%Y-%m-01') THEN paid_amount END), 0) AS month_gmv, " +
                "  COALESCE(COUNT(CASE WHEN order_time >= DATE_FORMAT(NOW(), '%Y-%m-01') THEN 1 END), 0) AS month_order_cnt " +
                "FROM order_sync " +
                "WHERE order_status = 1 " +
                "  AND order_time >= DATE_FORMAT(DATE_SUB(NOW(), INTERVAL 1 DAY), '%Y-%m-01')",
                (rs, i) -> GmvOverviewVO.builder()
                        .todayGmv(rs.getBigDecimal("today_gmv"))
                        .todayOrderCnt(rs.getLong("today_order_cnt"))
                        .todayPayUserCnt(rs.getLong("today_pay_user_cnt"))
                        .yesterdayGmv(rs.getBigDecimal("yesterday_gmv"))
                        .monthGmv(rs.getBigDecimal("month_gmv"))
                        .monthOrderCnt(rs.getLong("month_order_cnt"))
                        .build());

        if (vo == null) return new GmvOverviewVO();

        // 2) 本月退款金额
        BigDecimal refund = mysqlJdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(paid_amount), 0) FROM order_sync " +
                "WHERE order_status = 3 AND order_time >= DATE_FORMAT(NOW(), '%Y-%m-01')",
                BigDecimal.class);
        vo.setMonthRefundAmount(refund == null ? BigDecimal.ZERO : refund);
        vo.setMonthNetGmv(vo.getMonthGmv().subtract(vo.getMonthRefundAmount()));

        // 3) 今日全站 UV（从 ClickHouse 实时取，MySQL 无此数据）
        try {
            Long todayUv = ckJdbcTemplate.queryForObject(
                    "SELECT uniq(device_id) FROM ecom_analytics.events_local WHERE event_date = today()",
                    Long.class);
            vo.setTodayUv(todayUv == null ? 0L : todayUv);
        } catch (Exception e) {
            log.warn("fetch today UV from CK failed, fallback to 0", e);
            vo.setTodayUv(0L);
        }

        // 4) 衍生指标
        if (vo.getTodayUv() > 0) {
            vo.setTodayConversionRate(
                    round((double) vo.getTodayOrderCnt() / vo.getTodayUv()));
        } else {
            vo.setTodayConversionRate(0.0);
        }

        BigDecimal todayGmv = vo.getTodayGmv();
        BigDecimal yestGmv  = vo.getYesterdayGmv();
        if (yestGmv != null && yestGmv.compareTo(BigDecimal.ZERO) > 0) {
            vo.setDayOnDayRate(round(
                    todayGmv.subtract(yestGmv).doubleValue() / yestGmv.doubleValue()));
        } else {
            vo.setDayOnDayRate(0.0);
        }

        if (vo.getTodayPayUserCnt() > 0 && todayGmv != null) {
            vo.setTodayArpu(todayGmv.divide(
                    BigDecimal.valueOf(vo.getTodayPayUserCnt()), 2, RoundingMode.HALF_UP));
        } else {
            vo.setTodayArpu(BigDecimal.ZERO);
        }

        return vo;
    }

    // ═══════════════════════════════════════════════════
    //  2. GMV 趋势折线图（30min 缓存，防运营并发刷）
    // ═══════════════════════════════════════════════════

    /**
     * 平台 GMV 趋势（近 N 天，每天一个点）
     *
     * 优先走 platform_daily 聚合表；若该表暂无数据（刚上线/补数场景），
     * 自动降级为直接聚合 order_sync，保证接口不空返回。
     *
     * @param days 近几天，最大 90
     */
    @Cacheable(value = "gmvTrend", key = "#days")
    public List<GmvTrendPointVO> gmvTrend(int days) {
        int safeDays = Math.min(days, 90);
        LocalDate from = LocalDate.now().minusDays(safeDays - 1);

        // 优先走聚合表
        List<GmvTrendPointVO> result = mysqlJdbcTemplate.query(
                "SELECT event_date, gmv, pay_order_cnt AS order_cnt, pay_user_cnt, uv, " +
                "       pay_rate, refund_amount " +
                "FROM platform_daily " +
                "WHERE event_date >= ? ORDER BY event_date",
                (rs, i) -> GmvTrendPointVO.builder()
                        .date(rs.getDate("event_date").toLocalDate())
                        .gmv(rs.getBigDecimal("gmv"))
                        .orderCnt(rs.getLong("order_cnt"))
                        .payUserCnt(rs.getLong("pay_user_cnt"))
                        .uv(rs.getLong("uv"))
                        .payRate(rs.getDouble("pay_rate"))
                        .refundAmount(rs.getBigDecimal("refund_amount"))
                        .build(),
                Date.valueOf(from));

        // 降级：聚合表暂无数据时从 order_sync 实时聚合
        if (result.isEmpty()) {
            log.info("platform_daily empty, fallback to real-time aggregation from order_sync");
            result = mysqlJdbcTemplate.query(
                    "SELECT DATE(order_time) AS dt, " +
                    "  COALESCE(SUM(paid_amount), 0) AS gmv, " +
                    "  COUNT(*) AS order_cnt, " +
                    "  COUNT(DISTINCT user_id) AS pay_user_cnt " +
                    "FROM order_sync " +
                    "WHERE order_status = 1 AND order_time >= ? " +
                    "GROUP BY DATE(order_time) ORDER BY dt",
                    (rs, i) -> GmvTrendPointVO.builder()
                            .date(rs.getDate("dt").toLocalDate())
                            .gmv(rs.getBigDecimal("gmv"))
                            .orderCnt(rs.getLong("order_cnt"))
                            .payUserCnt(rs.getLong("pay_user_cnt"))
                            .uv(0L)
                            .payRate(0.0)
                            .refundAmount(BigDecimal.ZERO)
                            .build(),
                    Date.valueOf(from));
        }

        return fillDateGaps(result, from, LocalDate.now());
    }

    // ═══════════════════════════════════════════════════
    //  3. 类目统计（各类目 PV/UV/GMV，60min 缓存）
    // ═══════════════════════════════════════════════════

    /**
     * 各类目聚合统计
     *
     * 计算 GMV 占比（gmvRate），方便运营看各类目贡献度做饼图。
     * 同时计算加购率和支付转化率，识别哪些类目转化效率低。
     *
     * @param fromDate 开始日期，格式 yyyy-MM-dd
     * @param toDate   结束日期，格式 yyyy-MM-dd（含）
     */
    @Cacheable(value = "categoryStats", key = "#fromDate + ':' + #toDate")
    public List<CategoryStatVO> categoryStats(String fromDate, String toDate) {
        List<CategoryStatVO> list = mysqlJdbcTemplate.query(
                "SELECT category, " +
                "  SUM(pv) AS pv, SUM(uv) AS uv, SUM(search_cnt) AS search_cnt, " +
                "  SUM(add_cart_cnt) AS add_cart_cnt, SUM(create_order_cnt) AS create_order_cnt, " +
                "  SUM(pay_cnt) AS pay_cnt, SUM(pay_amount) AS pay_amount, " +
                "  SUM(gmv) AS gmv, SUM(item_cnt) AS item_cnt " +
                "FROM category_daily " +
                "WHERE event_date BETWEEN ? AND ? AND category IS NOT NULL AND category != '' " +
                "GROUP BY category ORDER BY gmv DESC",
                (rs, i) -> CategoryStatVO.builder()
                        .category(rs.getString("category"))
                        .pv(rs.getLong("pv"))
                        .uv(rs.getLong("uv"))
                        .searchCnt(rs.getLong("search_cnt"))
                        .addCartCnt(rs.getLong("add_cart_cnt"))
                        .createOrderCnt(rs.getLong("create_order_cnt"))
                        .payCnt(rs.getLong("pay_cnt"))
                        .payAmount(rs.getBigDecimal("pay_amount"))
                        .gmv(rs.getBigDecimal("gmv"))
                        .itemCnt(rs.getLong("item_cnt"))
                        .build(),
                fromDate, toDate);

        // 计算 GMV 占比、加购率、支付转化率
        BigDecimal totalGmv = list.stream()
                .map(CategoryStatVO::getGmv)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        for (CategoryStatVO vo : list) {
            // GMV 占比
            if (totalGmv.compareTo(BigDecimal.ZERO) > 0) {
                vo.setGmvRate(round(vo.getGmv().doubleValue() / totalGmv.doubleValue()));
            } else {
                vo.setGmvRate(0.0);
            }
            // 加购率 = 加购次数 / 浏览量
            vo.setCartRate(vo.getPv() > 0 ? round((double) vo.getAddCartCnt() / vo.getPv()) : 0.0);
            // 支付转化率 = 支付人数 / UV（用 payCnt 近似，准确需去重，此处允许近似）
            vo.setPayRate(vo.getUv() > 0 ? round((double) vo.getPayCnt() / vo.getUv()) : 0.0);
        }
        return list;
    }

    // ─── 工具方法 ──────────────────────────────────────

    private double round(double val) {
        return Math.round(val * 10000.0) / 10000.0;
    }

    /**
     * 补全缺失日期（数据库无数据的天填 0，保证折线图日期连续）
     */
    private List<GmvTrendPointVO> fillDateGaps(List<GmvTrendPointVO> data,
                                               LocalDate from, LocalDate to) {
        List<GmvTrendPointVO> out = new ArrayList<>();
        int idx = 0;
        for (LocalDate cur = from; !cur.isAfter(to); cur = cur.plusDays(1)) {
            final LocalDate d = cur;
            if (idx < data.size() && data.get(idx).getDate().equals(d)) {
                out.add(data.get(idx++));
            } else {
                out.add(GmvTrendPointVO.builder()
                        .date(d).gmv(BigDecimal.ZERO).orderCnt(0L)
                        .payUserCnt(0L).uv(0L).payRate(0.0)
                        .refundAmount(BigDecimal.ZERO).build());
            }
        }
        return out;
    }
}
