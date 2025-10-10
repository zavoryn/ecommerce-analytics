package com.ecom.analytics.query.service;

import com.ecom.analytics.query.dto.FunnelStepVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 漏斗分析服务(面试稿 1.2 / 1.7)
 *
 * 使用 ClickHouse 原生 windowFunnel 函数,在指定时间窗口内匹配有序事件,
 * 自动统计每一步留存。MySQL 写漏斗需要复杂的多次自连接,这里直接走 OLAP。
 *
 * 设计:
 *  - 按 device_id 分组(未登录用户也能算,登录后通过 ID-Mapping 归并到 user_id);
 *  - 步骤:search -> view_item -> add_cart -> pay_order;
 *  - 窗口默认 2 小时,可由调用方传入。
 *
 * 注意：只有一个 ClickHouse JdbcTemplate Bean 需要注入，但 query 模块同时存在
 * mysqlJdbcTemplate（来自 DataSourceConfig），Spring 仍需 @Qualifier 区分。
 * 使用显式构造器注入而非 @RequiredArgsConstructor，避免 Lombok 丢失 @Qualifier。
 */
@Slf4j
@Service
public class FunnelService {

    private final JdbcTemplate ckJdbcTemplate;

    public FunnelService(@Qualifier("clickHouseJdbcTemplate") JdbcTemplate ckJdbcTemplate) {
        this.ckJdbcTemplate = ckJdbcTemplate;
    }

    /**
     * 单商品专项转化漏斗（面试稿 1.2 / 运营选品追问场景）
     *
     * 场景：运营发现某款连衣裙 PV 很高但 GMV 很低，
     *       想知道卡在哪一步（浏览 → 加购 → 下单 → 支付）转化掉的。
     *
     * 步骤：view_item → add_cart → create_order → pay_order（2小时窗口内完成）
     *
     * 区别于全平台漏斗：
     *  - 全平台：FROM events_local WHERE event_date BETWEEN ?
     *  - 商品专项：WHERE item_id = ? AND event_date BETWEEN ?（只看这个商品的用户路径）
     *
     * @param itemId    商品 ID
     * @param fromDate  开始日期
     * @param toDate    结束日期
     */
    public List<FunnelStepVO> itemFunnel(Long itemId, String fromDate, String toDate) {
        String[] itemSteps = {"view_item", "add_cart", "create_order", "pay_order"};
        String sql =
                "SELECT level, count() AS cnt FROM (" +
                "  SELECT device_id," +
                "    windowFunnel(7200)(event_time," +
                "      event_name = 'view_item'," +
                "      event_name = 'add_cart'," +
                "      event_name = 'create_order'," +
                "      event_name = 'pay_order') AS level" +
                "  FROM ecom_analytics.events_local" +
                "  WHERE item_id = ?" +
                "    AND event_date BETWEEN ? AND ?" +
                "  GROUP BY device_id" +
                ") GROUP BY level ORDER BY level DESC";

        long[] reachAtLeast = new long[itemSteps.length + 1];
        ckJdbcTemplate.query(sql,
                rs -> {
                    int level = rs.getInt("level");
                    long cnt  = rs.getLong("cnt");
                    if (level >= 1 && level <= itemSteps.length) {
                        reachAtLeast[level] = cnt;
                    }
                },
                itemId, fromDate, toDate);

        // 累加：reach[k] = sum(reach[k..N])
        for (int k = itemSteps.length - 1; k >= 1; k--) {
            reachAtLeast[k] += reachAtLeast[k + 1];
        }

        List<FunnelStepVO> out = new ArrayList<>(itemSteps.length);
        long prev = 0;
        for (int i = 0; i < itemSteps.length; i++) {
            long cnt  = reachAtLeast[i + 1];
            double rate = (i == 0 || prev == 0) ? 1.0 : (double) cnt / prev;
            out.add(new FunnelStepVO(itemSteps[i], cnt, rate));
            prev = cnt;
        }
        return out;
    }

    private static final String[] STEPS = {"search", "view_item", "add_cart", "pay_order"};

    public List<FunnelStepVO> funnel(String fromDate, String toDate, int windowSeconds) {
        String sql = "SELECT level, count() AS cnt FROM ( " +
                "  SELECT device_id, windowFunnel(?)(event_time, " +
                "      event_name = 'search', " +
                "      event_name = 'view_item', " +
                "      event_name = 'add_cart', " +
                "      event_name = 'pay_order') AS level " +
                "  FROM events_local " +
                "  WHERE event_date BETWEEN ? AND ? " +
                "  GROUP BY device_id" +
                ") GROUP BY level ORDER BY level DESC";

        // CK 返回的 level: 完成第几步(0=没匹配上, k=匹配到第k步), 从大到小累加得到每步人数
        long[] reachAtLeast = new long[STEPS.length + 1];
        ckJdbcTemplate.query(sql,
                rs -> {
                    int level = rs.getInt("level");
                    long cnt = rs.getLong("cnt");
                    if (level >= 1 && level <= STEPS.length) {
                        reachAtLeast[level] = cnt;
                    }
                },
                windowSeconds, fromDate, toDate);

        // 累加:reach[k] = sum(reach[k..N])
        for (int k = STEPS.length - 1; k >= 1; k--) {
            reachAtLeast[k] += reachAtLeast[k + 1];
        }

        List<FunnelStepVO> out = new ArrayList<>(STEPS.length);
        long prev = 0;
        for (int i = 0; i < STEPS.length; i++) {
            long cnt = reachAtLeast[i + 1];
            double rate = (i == 0 || prev == 0) ? 1.0 : (double) cnt / prev;
            out.add(new FunnelStepVO(STEPS[i], cnt, rate));
            prev = cnt;
        }
        return out;
    }
}
