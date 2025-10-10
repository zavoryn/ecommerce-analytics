package com.ecom.analytics.query.controller;

import com.alibaba.csp.sentinel.annotation.SentinelResource;
import com.ecom.analytics.common.response.R;
import com.ecom.analytics.query.dto.CategoryStatVO;
import com.ecom.analytics.query.dto.GmvOverviewVO;
import com.ecom.analytics.query.dto.GmvTrendPointVO;
import com.ecom.analytics.query.service.OperationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * 运营大盘接口
 *
 * 面向运营人员（不对外暴露给 C 端用户）：
 *  - 首页：实时 GMV 概览卡片（今日/昨日/本月）
 *  - 趋势：近 N 天 GMV/订单量/UV 折线图
 *  - 类目：各类目 PV/UV/GMV 对比
 *
 * 限流：Sentinel 200 QPS/IP（运营人员少，不需要高并发）
 * 缓存：趋势/类目接口 30~60min 缓存；概览接口不缓存（需实时）
 */
@RestController
@RequestMapping("/api/operation")
@RequiredArgsConstructor
@Tag(name = "运营大盘", description = "GMV概览 / 趋势 / 类目分析")
public class OperationController {

    private final OperationService operationService;

    /**
     * 平台 GMV 大盘概览
     *
     * 运营首页核心指标卡片：今日实时 GMV、订单数、UV、支付转化率，
     * 及与昨日环比、本月累计。不走 Redis 缓存（需实时数据）。
     *
     * 场景示例：
     *   运营早会打开大屏，查看昨夜 0 点到现在的实时数据
     */
    @Operation(summary = "GMV大盘概览",
            description = "今日实时 GMV/订单/UV + 日环比 + 本月累计。不走缓存，每次返回最新数据。")
    @SentinelResource(value = "operationOverview", blockHandler = "blockFallback")
    @GetMapping("/overview")
    public R<GmvOverviewVO> overview() {
        return R.ok(operationService.overview());
    }

    /**
     * 平台 GMV 趋势折线图
     *
     * 运营大盘核心图表，可切换 7/30/90 天周期。
     * 用于判断：大促后 GMV 是否如预期回落、新品上线是否带来增量。
     *
     * 场景示例：
     *   双十一结束后，复盘近 30 天 GMV 走势，对比活动期 vs 活动后恢复情况
     */
    @Operation(summary = "GMV趋势",
            description = "近 N 天每日 GMV/订单/UV 趋势。走 platform_daily 聚合表，30min 缓存。")
    @SentinelResource(value = "operationGmvTrend", blockHandler = "blockFallback")
    @GetMapping("/gmv-trend")
    public R<List<GmvTrendPointVO>> gmvTrend(
            @Parameter(description = "近几天，可选7/30/90，最大90")
            @RequestParam(defaultValue = "7") int days) {
        return R.ok(operationService.gmvTrend(days));
    }

    /**
     * 各类目统计分析
     *
     * 运营选品决策场景：
     *  - 横向对比各类目 GMV 占比，判断哪个品类还有增长空间
     *  - 纵向看加购率/支付转化率，识别流量好但转化差的类目
     *
     * 场景示例：
     *   选品会议中，运营发现"美妆"类目 UV 高但 GMV 占比低（说明客单价低或转化差），
     *   决定下月引入高客单美妆品牌并安排首页 Banner 曝光
     */
    @Operation(summary = "类目统计分析",
            description = "指定时间范围内各类目 PV/UV/GMV/转化率，含 GMV 占比。60min 缓存。")
    @SentinelResource(value = "operationCategoryStats", blockHandler = "blockFallback")
    @GetMapping("/category-stats")
    public R<List<CategoryStatVO>> categoryStats(
            @Parameter(description = "开始日期 yyyy-MM-dd，默认 7 天前")
            @RequestParam(required = false) String fromDate,
            @Parameter(description = "结束日期 yyyy-MM-dd，默认昨日")
            @RequestParam(required = false) String toDate) {
        // 默认近 7 天
        if (fromDate == null) fromDate = LocalDate.now().minusDays(6).toString();
        if (toDate   == null) toDate   = LocalDate.now().minusDays(1).toString();
        return R.ok(operationService.categoryStats(fromDate, toDate));
    }

    @SuppressWarnings("unused")
    public R<?> blockFallback(Object... args) {
        return R.fail(429, "请求过于频繁，稍后再试");
    }
}
