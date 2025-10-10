package com.ecom.analytics.query.controller;

import com.alibaba.csp.sentinel.annotation.SentinelResource;
import com.ecom.analytics.common.response.R;
import com.ecom.analytics.query.dto.FunnelStepVO;
import com.ecom.analytics.query.dto.TrendPointVO;
import com.ecom.analytics.query.service.FunnelService;
import com.ecom.analytics.query.service.TrendService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 趋势 / 漏斗 查询接口
 *
 * 设计要点(面试稿 2.7 / 2.10):
 *  - 30 天趋势走聚合表 + Redis 缓存(10min);
 *  - 实时(近 1h)走明细表 + 缓存合并;
 *  - 漏斗走 ClickHouse windowFunnel;
 *  - Sentinel 限流:每用户 10 QPM,防恶意刷接口。
 */
@RestController
@RequestMapping("/api/query")
@RequiredArgsConstructor
@Tag(name = "数据查询", description = "趋势 / 漏斗")
public class TrendController {

    private final TrendService trendService;
    private final FunnelService funnelService;

    @Operation(summary = "查询商品近 N 天日销售/曝光趋势")
    @SentinelResource(value = "itemTrend", blockHandler = "blockFallback")
    @GetMapping("/item-trend")
    public R<List<TrendPointVO>> itemTrend(
            @RequestParam Long itemId,
            @RequestParam(defaultValue = "7") int days) {
        return R.ok(trendService.trend(itemId, days));
    }

    @Operation(summary = "全平台漏斗分析(search -> view -> add_cart -> pay)",
            description = "ClickHouse windowFunnel 统计全站用户在指定时间窗口内的四步转化路径。")
    @SentinelResource(value = "funnel", blockHandler = "blockFallback")
    @GetMapping("/funnel")
    public R<List<FunnelStepVO>> funnel(
            @RequestParam String fromDate,
            @RequestParam String toDate,
            @RequestParam(defaultValue = "7200") int windowSeconds) {
        return R.ok(funnelService.funnel(fromDate, toDate, windowSeconds));
    }

    /**
     * 单商品专项转化漏斗（运营选品追问场景）
     *
     * 场景：运营发现某商品 PV 很高但 GMV 低，分析卡在哪步：
     *   浏览 → 加购 → 创建订单 → 支付（2 小时窗口内）
     *
     * 用法示例：
     *   GET /api/query/item-funnel?itemId=888&fromDate=2025-01-08&toDate=2025-01-15
     *
     * 返回：[{step:"view_item",userCount:5000,conversionRate:1.0},
     *        {step:"add_cart",  userCount:1500,conversionRate:0.30},
     *        {step:"create_order",userCount:600, conversionRate:0.40},
     *        {step:"pay_order",  userCount:500, conversionRate:0.83}]
     * → 加购率仅 30%，说明商品详情页或价格有问题
     */
    @Operation(summary = "单商品转化漏斗(view -> add_cart -> create_order -> pay)",
            description = "指定商品在时间范围内的 view_item→add_cart→create_order→pay_order 漏斗。")
    @SentinelResource(value = "itemFunnel", blockHandler = "blockFallback")
    @GetMapping("/item-funnel")
    public R<List<FunnelStepVO>> itemFunnel(
            @RequestParam Long itemId,
            @RequestParam String fromDate,
            @RequestParam String toDate) {
        return R.ok(funnelService.itemFunnel(itemId, fromDate, toDate));
    }

    @SuppressWarnings("unused")
    public R<?> blockFallback(Object... args) {
        return R.fail(429, "请求过于频繁，稍后再试");
    }
}
