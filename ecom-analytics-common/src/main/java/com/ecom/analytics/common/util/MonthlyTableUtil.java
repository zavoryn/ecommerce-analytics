package com.ecom.analytics.common.util;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 按月分表的表名路由工具
 *
 * 设计思路:用户行为明细按月分表,如 event_detail_202502。
 * 查询时根据时间范围计算需要扫描哪些子表,在 Service 层做 UNION ALL,
 * 由 Query 层负责聚合与排序(深分页场景建议走 ES search_after,见 search 模块)。
 */
public final class MonthlyTableUtil {

    private MonthlyTableUtil() {}

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyyMM");
    public static final String PREFIX = "event_detail_";

    public static String tableOf(LocalDateTime time) {
        return PREFIX + time.format(FMT);
    }

    public static String tableOf(LocalDate date) {
        return PREFIX + date.format(FMT);
    }

    /** 根据时间范围返回涉及的表名(升序) */
    public static List<String> tablesBetween(LocalDate from, LocalDate to) {
        List<String> tables = new ArrayList<>();
        LocalDate cursor = from.withDayOfMonth(1);
        while (!cursor.isAfter(to)) {
            tables.add(tableOf(cursor));
            cursor = cursor.plusMonths(1);
        }
        return tables;
    }
}
