package com.ecom.analytics.query.service;

import com.ecom.analytics.query.cache.MultiLevelCache;
import com.ecom.analytics.query.dto.CategoryStatVO;
import com.ecom.analytics.query.dto.HotKeywordVO;
import com.ecom.analytics.query.dto.TopItemVO;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 排行榜服务（运营选品核心）
 *
 * 提供三大排行场景（面试稿 2.7 / 2.10）：
 *
 * 1. TOP 商品榜（topItems）：
 *    - 按 GMV / 销量 / 浏览量 多维度排行
 *    - 支持按类目过滤（运营选品找某类目爆款）
 *    - 数据来源：order_sync(GMV/销量) JOIN product_info(商品名/品牌) JOIN event_agg_daily(PV/UV)
 *    - 排名变化：与 item_ranking_daily 历史数据对比，显示↑↓
 *
 * 2. 热搜词榜（hotKeywords）：
 *    - 历史：search_keyword_daily（DailyAggregateTask T+1 计算）
 *    - 实时：直接查 ClickHouse events_local（今日数据）
 *    - 趋势标记：与昨日对比，飙升/稳定/下降
 *
 * 3. TOP 类目榜（topCategories）：
 *    - 各类目近 N 天 GMV 排行
 *    - 数据来源：category_daily
 *
 * 注意：双数据源，使用显式构造器 + @Qualifier 注入。
 */
@Slf4j
@Service
public class RankingService {

    private final JdbcTemplate mysqlJdbcTemplate;
    private final JdbcTemplate ckJdbcTemplate;
    private final MultiLevelCache multiLevelCache;

    public RankingService(JdbcTemplate mysqlJdbcTemplate,
                          @Qualifier("clickHouseJdbcTemplate") JdbcTemplate ckJdbcTemplate,
                          MultiLevelCache multiLevelCache) {
        this.mysqlJdbcTemplate = mysqlJdbcTemplate;
        this.ckJdbcTemplate = ckJdbcTemplate;
        this.multiLevelCache = multiLevelCache;
    }

    // ═══════════════════════════════════════════════════
    //  1. TOP 商品排行榜
    // ═══════════════════════════════════════════════════

    /**
     * 商品排行榜
     *
     * 缓存策略 (Stage 3.5 升级):
     *   L1 Caffeine 1min - 防止运营页面频繁刷新打 Redis
     *   L2 Redis 10min (含三防: 空值 / 互斥锁 / TTL 抖动)
     *
     * 数据策略:
     *   优先走预计算的 item_ranking_daily(凌晨聚合, &lt; 5ms),
     *   该表无近期数据时降级为实时聚合 order_sync。
     *
     * @param rankBy   排序维度: gmv(默认) / pay_cnt / pv / add_cart_cnt
     * @param days     近几天, 推荐 7 / 30
     * @param category 类目过滤, null 表示全类目
     * @param limit    返回条数, 最大 100
     */
    public List<TopItemVO> topItems(String rankBy, int days, String category, int limit) {
        int safeLimit = Math.min(limit, 100);
        String safeRankBy = List.of("gmv", "pay_cnt", "pv", "add_cart_cnt").contains(rankBy)
                ? rankBy : "gmv";
        String cacheKey = "topItems:" + safeRankBy + ":" + days + ":" + category + ":" + safeLimit;

        return multiLevelCache.getOrLoad("topItems", cacheKey,
                new TypeReference<List<TopItemVO>>() {},
                600L,   // Redis TTL 10 分钟, MultiLevelCache 自带 10% 随机抖动防雪崩
                () -> doTopItems(safeRankBy, days, category, safeLimit));
    }

    private List<TopItemVO> doTopItems(String rankBy, int days, String category, int limit) {
        // 1) 优先走 item_ranking_daily(预计算, 含昨日排名)
        List<TopItemVO> result = queryFromRankingTable(rankBy, category, limit);

        // 2) 降级: 实时从 order_sync + product_info + event_agg_daily 聚合
        if (result.isEmpty()) {
            log.info("item_ranking_daily empty for rankBy={}, fallback to real-time", rankBy);
            result = realtimeTopItems(rankBy, days, category, limit);
        }
        return result;
    }

    private List<TopItemVO> queryFromRankingTable(String rankType, String category, int limit) {
        LocalDate today = LocalDate.now();
        // 优先看今天，否则取昨天（凌晨任务可能还没跑完）
        for (LocalDate d : List.of(today, today.minusDays(1))) {
            List<TopItemVO> rows = mysqlJdbcTemplate.query(
                    "SELECT r.rank_no, r.prev_rank_no, r.item_id, r.item_name, r.brand, " +
                    "  r.item_category AS category, r.pv, r.uv, r.add_cart_cnt, " +
                    "  r.pay_cnt, r.pay_amount, r.gmv " +
                    "FROM item_ranking_daily r " +
                    "WHERE r.rank_date = ? AND r.rank_type = ? " +
                    (StringUtils.hasText(category) ? " AND r.category = ? " : " AND r.category IS NULL ") +
                    "ORDER BY r.rank_no LIMIT ?",
                    StringUtils.hasText(category)
                            ? new Object[]{java.sql.Date.valueOf(d), rankType, category, limit}
                            : new Object[]{java.sql.Date.valueOf(d), rankType, limit},
                    (rs, i) -> {
                        int rank = rs.getInt("rank_no");
                        // 必须先读 prev_rank_no，再立即调用 wasNull()
                        // rs.wasNull() 检查的是最后一次 getXxx 的列是否为 NULL
                        long prevRankRaw = rs.getLong("prev_rank_no");
                        Integer prevRank = rs.wasNull() ? null : (int) prevRankRaw;
                        String change = buildRankChange(rank, prevRank);
                        long pv = rs.getLong("pv");
                        long addCart = rs.getLong("add_cart_cnt");
                        long uv = rs.getLong("uv");
                        long payCnt = rs.getLong("pay_cnt");
                        return TopItemVO.builder()
                                .rank(rank)
                                .prevRank(prevRank)
                                .rankChange(change)
                                .itemId(rs.getLong("item_id"))
                                .itemName(rs.getString("item_name"))
                                .category(rs.getString("category"))
                                .brand(rs.getString("brand"))
                                .pv(pv)
                                .uv(uv)
                                .addCartCnt(addCart)
                                .payCnt(payCnt)
                                .payAmount(rs.getBigDecimal("pay_amount"))
                                .gmv(rs.getBigDecimal("gmv"))
                                .cartRate(pv > 0 ? Math.round((double) addCart / pv * 10000.0) / 10000.0 : 0.0)
                                .payRate(uv > 0 ? Math.round((double) payCnt / uv * 10000.0) / 10000.0 : 0.0)
                                .build();
                    });
            if (!rows.isEmpty()) return rows;
        }
        return new ArrayList<>();
    }

    /**
     * 降级：实时聚合 order_sync + product_info + event_agg_daily
     * 无排名变化信息（实时计算时没有历史对比），其余字段完整。
     *
     * 安全说明: rankBy 来自上层白名单 switch, orderCol 拼接安全;
     *          category 来自外部运营查询参数, 必须全参数化(? IS NULL OR ...)。
     */
    private List<TopItemVO> realtimeTopItems(String rankBy, int days, String category, int limit) {
        String orderCol = switch (rankBy) {
            case "pay_cnt" -> "pay_cnt";
            case "pv"      -> "pv";
            case "add_cart_cnt" -> "add_cart_cnt";
            default        -> "gmv";
        };

        // category 参数化: null 走"全类目", 非空走"指定类目"; 同一占位符绑定两次
        String safeCategory = StringUtils.hasText(category) ? category : null;
        String sql =
                "SELECT o.item_id, " +
                "  COALESCE(p.title, CONCAT('商品-', o.item_id)) AS item_name, " +
                "  COALESCE(p.brand, '') AS brand, " +
                "  o.category, " +
                "  COALESCE(SUM(o.paid_amount), 0) AS gmv, " +
                "  COUNT(*) AS pay_cnt, " +
                "  COALESCE(SUM(a.pv), 0) AS pv, " +
                "  COALESCE(SUM(a.uv), 0) AS uv, " +
                "  COALESCE(SUM(a.add_cart_cnt), 0) AS add_cart_cnt " +
                "FROM order_sync o " +
                "LEFT JOIN product_info p ON o.item_id = p.item_id " +
                "LEFT JOIN event_agg_daily a ON o.item_id = a.item_id " +
                "  AND a.event_date >= DATE_SUB(CURDATE(), INTERVAL ? DAY) " +
                "WHERE o.order_status = 1 " +
                "  AND o.order_time >= DATE_SUB(NOW(), INTERVAL ? DAY) " +
                "  AND (? IS NULL OR o.category = ?) " +
                "GROUP BY o.item_id, p.title, p.brand, o.category " +
                "ORDER BY " + orderCol + " DESC " +
                "LIMIT ?";

        List<TopItemVO> rows = mysqlJdbcTemplate.query(sql,
                (rs, i) -> {
                    int rank = i + 1;
                    long pv = rs.getLong("pv");
                    long addCart = rs.getLong("add_cart_cnt");
                    long uv = rs.getLong("uv");
                    long payCnt = rs.getLong("pay_cnt");
                    return TopItemVO.builder()
                            .rank(rank)
                            .prevRank(null)
                            .rankChange("--")
                            .itemId(rs.getLong("item_id"))
                            .itemName(rs.getString("item_name"))
                            .category(rs.getString("category"))
                            .brand(rs.getString("brand"))
                            .gmv(rs.getBigDecimal("gmv"))
                            .payCnt(payCnt)
                            .pv(pv)
                            .uv(uv)
                            .addCartCnt(addCart)
                            .cartRate(pv > 0 ? Math.round((double) addCart / pv * 10000.0) / 10000.0 : 0.0)
                            .payRate(uv > 0 ? Math.round((double) payCnt / uv * 10000.0) / 10000.0 : 0.0)
                            .build();
                },
                days, days, safeCategory, safeCategory, limit);
        return rows;
    }

    // ═══════════════════════════════════════════════════
    //  2. 热搜词排行榜
    // ═══════════════════════════════════════════════════

    /**
     * 热搜词排行
     *
     * 今日数据走 ClickHouse 实时统计（秒级延迟）；
     * 历史日期走 MySQL search_keyword_daily（凌晨预计算，< 5ms）。
     *
     * CTR / CVR 超过 1 时做截断（异常数据兜底）。
     *
     * @param date  查询日期，null 或 "today" 表示实时
     * @param limit 返回条数，最大 50
     */
    @Cacheable(value = "hotKeywords", key = "#date + ':' + #limit")
    public List<HotKeywordVO> hotKeywords(String date, int limit) {
        int safeLimit = Math.min(limit, 50);
        boolean isToday = !StringUtils.hasText(date) || "today".equalsIgnoreCase(date)
                          || LocalDate.now().toString().equals(date);

        if (isToday) {
            return realtimeHotKeywords(safeLimit);
        }
        return historyHotKeywords(date, safeLimit);
    }

    /** 今日实时热搜词：直接查 ClickHouse events_local */
    private List<HotKeywordVO> realtimeHotKeywords(int limit) {
        try {
            // 同时查昨日同词搜索量，用于计算趋势
            String sql =
                    "SELECT keyword, today_cnt, uv, " +
                    "  multiIf(yesterday_cnt = 0, 'new', " +
                    "           today_cnt > yesterday_cnt * 1.5, 'rising', " +
                    "           today_cnt < yesterday_cnt * 0.7, 'falling', 'stable') AS trend " +
                    "FROM (" +
                    "  SELECT " +
                    "    JSONExtractString(properties, 'keyword') AS keyword, " +
                    "    countIf(event_date = today()) AS today_cnt, " +
                    "    uniqIf(device_id, event_date = today()) AS uv, " +
                    "    countIf(event_date = yesterday()) AS yesterday_cnt " +
                    "  FROM ecom_analytics.events_local " +
                    "  WHERE event_name = 'search' " +
                    "    AND event_date IN (today(), yesterday()) " +
                    "    AND notEmpty(JSONExtractString(properties, 'keyword')) " +
                    "  GROUP BY keyword " +
                    "  HAVING today_cnt > 0" +
                    ") ORDER BY today_cnt DESC LIMIT ?";

            List<HotKeywordVO> result = ckJdbcTemplate.query(sql,
                    (rs, i) -> HotKeywordVO.builder()
                            .rank(i + 1)
                            .keyword(rs.getString("keyword"))
                            .searchCnt(rs.getLong("today_cnt"))
                            .uv(rs.getLong("uv"))
                            .clickCnt(0L)   // 实时简化：click 需额外 join session
                            .payCnt(0L)
                            .payAmount(BigDecimal.ZERO)
                            .ctr(0.0)
                            .cvr(0.0)
                            .trend(rs.getString("trend"))
                            .build(),
                    limit);
            return result;
        } catch (Exception e) {
            log.warn("CK realtime hot keyword query failed, fallback to yesterday history", e);
            return historyHotKeywords(LocalDate.now().minusDays(1).toString(), limit);
        }
    }

    /** 历史热搜词：走 MySQL search_keyword_daily */
    private List<HotKeywordVO> historyHotKeywords(String date, int limit) {
        return mysqlJdbcTemplate.query(
                "SELECT keyword, search_cnt, uv, click_cnt, pay_cnt, pay_amount, ctr, cvr " +
                "FROM search_keyword_daily " +
                "WHERE event_date = ? ORDER BY search_cnt DESC LIMIT ?",
                (rs, i) -> HotKeywordVO.builder()
                        .rank(i + 1)
                        .keyword(rs.getString("keyword"))
                        .searchCnt(rs.getLong("search_cnt"))
                        .uv(rs.getLong("uv"))
                        .clickCnt(rs.getLong("click_cnt"))
                        .payCnt(rs.getLong("pay_cnt"))
                        .payAmount(rs.getBigDecimal("pay_amount"))
                        .ctr(Math.min(rs.getDouble("ctr"), 1.0))
                        .cvr(Math.min(rs.getDouble("cvr"), 1.0))
                        .trend("stable")
                        .build(),
                date, limit);
    }

    // ═══════════════════════════════════════════════════
    //  3. TOP 类目排行榜
    // ═══════════════════════════════════════════════════

    /**
     * 近 N 天各类目 GMV 排行
     *
     * 从 category_daily 聚合，用于运营判断哪个品类需要增加流量/促销资源。
     * 结果含 GMV 占比，方便做饼图。
     *
     * @param days  近几天
     * @param limit 返回类目数，最大 20
     */
    @Cacheable(value = "topCategories", key = "#days + ':' + #limit")
    public List<CategoryStatVO> topCategories(int days, int limit) {
        int safeLimit = Math.min(limit, 20);
        List<CategoryStatVO> list = mysqlJdbcTemplate.query(
                "SELECT category, SUM(pv) AS pv, SUM(uv) AS uv, " +
                "  SUM(search_cnt) AS search_cnt, SUM(add_cart_cnt) AS add_cart_cnt, " +
                "  SUM(create_order_cnt) AS create_order_cnt, SUM(pay_cnt) AS pay_cnt, " +
                "  SUM(pay_amount) AS pay_amount, SUM(gmv) AS gmv, SUM(item_cnt) AS item_cnt " +
                "FROM category_daily " +
                "WHERE event_date >= DATE_SUB(CURDATE(), INTERVAL ? DAY) " +
                "  AND category IS NOT NULL AND category != '' " +
                "GROUP BY category ORDER BY gmv DESC LIMIT ?",
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
                days, safeLimit);

        // 计算 GMV 占比
        BigDecimal total = list.stream().map(CategoryStatVO::getGmv)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        for (CategoryStatVO vo : list) {
            double rate = total.compareTo(BigDecimal.ZERO) > 0
                    ? Math.round(vo.getGmv().doubleValue() / total.doubleValue() * 10000.0) / 10000.0
                    : 0.0;
            vo.setGmvRate(rate);
            long pv = vo.getPv();
            vo.setCartRate(pv > 0
                    ? Math.round((double) vo.getAddCartCnt() / pv * 10000.0) / 10000.0 : 0.0);
            long uv = vo.getUv();
            vo.setPayRate(uv > 0
                    ? Math.round((double) vo.getPayCnt() / uv * 10000.0) / 10000.0 : 0.0);
        }
        return list;
    }

    // ─── 工具方法 ──────────────────────────────────────

    private String buildRankChange(int rank, Integer prevRank) {
        if (prevRank == null) return "新上榜";
        int diff = prevRank - rank;
        if (diff > 0)  return "↑" + diff;
        if (diff < 0)  return "↓" + Math.abs(diff);
        return "持平";
    }
}
