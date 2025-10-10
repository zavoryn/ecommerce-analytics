package com.ecom.analytics.query.service;

import com.ecom.analytics.query.dto.TrendPointVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 商品趋势服务
 *
 * 关键策略(面试稿 2.7 / 2.10):
 *  1. 优先查聚合表 event_agg_daily(item_id+event_date 联合索引);
 *  2. 若当天还没聚合(updated_at < today),合并明细表数据 + 聚合表前 N-1 天;
 *  3. Redis 缓存 10min,key = item:trend:{itemId}:{days}。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TrendService {

    private final JdbcTemplate mysqlJdbcTemplate;

    @Cacheable(value = "itemTrend", key = "#itemId + ':' + #days")
    public List<TrendPointVO> trend(Long itemId, int days) {
        LocalDate to = LocalDate.now();
        LocalDate from = to.minusDays(days - 1);

        // 优先聚合表
        List<TrendPointVO> aggResult = mysqlJdbcTemplate.query(
                "SELECT event_date, pv, uv, pay_cnt FROM event_agg_daily " +
                        " WHERE item_id = ? AND event_date BETWEEN ? AND ? ORDER BY event_date",
                (rs, i) -> new TrendPointVO(
                        rs.getDate("event_date").toLocalDate(),
                        rs.getLong("pv"),
                        rs.getLong("uv"),
                        rs.getLong("pay_cnt")),
                itemId,
                java.sql.Date.valueOf(from),
                java.sql.Date.valueOf(to));

        // 当日数据若未在聚合表中,实时从明细表(当月分表)补 1 行
        boolean hasToday = aggResult.stream().anyMatch(p -> p.getDate().equals(to));
        if (!hasToday) {
            TrendPointVO today = realtimeToday(itemId, to);
            if (today != null) aggResult.add(today);
        }

        // 补全缺日期占位(可选)
        return fillGaps(aggResult, from, to);
    }

    private TrendPointVO realtimeToday(Long itemId, LocalDate today) {
        // 简化:从当月分表查;生产用 MonthlyTableUtil 路由
        try {
            String table = "event_detail_" + today.toString().replace("-", "").substring(0, 6);
            return mysqlJdbcTemplate.queryForObject(
                    "SELECT DATE(ts) d, COUNT(1) pv, COUNT(DISTINCT device_id) uv, " +
                            "  SUM(CASE WHEN event_name='pay_order' THEN 1 ELSE 0 END) pay_cnt " +
                            " FROM " + table +
                            " WHERE DATE(ts) = ? AND JSON_EXTRACT(ext_info,'$.item_id') = ?",
                    (rs, i) -> new TrendPointVO(rs.getDate("d").toLocalDate(),
                            rs.getLong("pv"), rs.getLong("uv"), rs.getLong("pay_cnt")),
                    java.sql.Date.valueOf(today), itemId);
        } catch (Exception e) {
            return null;
        }
    }

    private List<TrendPointVO> fillGaps(List<TrendPointVO> data, LocalDate from, LocalDate to) {
        List<TrendPointVO> out = new ArrayList<>();
        LocalDate cur = from;
        int idx = 0;
        while (!cur.isAfter(to)) {
            if (idx < data.size() && data.get(idx).getDate().equals(cur)) {
                out.add(data.get(idx));
                idx++;
            } else {
                out.add(new TrendPointVO(cur, 0L, 0L, 0L));
            }
            cur = cur.plusDays(1);
        }
        return out;
    }
}
