package com.ecom.analytics.query.controller;

import com.alibaba.csp.sentinel.annotation.SentinelResource;
import com.ecom.analytics.common.response.R;
import com.ecom.analytics.query.dto.CategoryStatVO;
import com.ecom.analytics.query.dto.HotKeywordVO;
import com.ecom.analytics.query.dto.TopItemVO;
import com.ecom.analytics.query.service.RankingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 排行榜接口（运营选品核心）
 *
 * 三大排行场景：
 *  1. TOP 商品：按 GMV / 销量 / 浏览量 多维度，支持类目过滤
 *  2. 热搜词：今日实时（CK）/ 历史（MySQL）
 *  3. TOP 类目：各品类 GMV 占比，辅助流量分配决策
 *
 * 限流：200 QPS/IP（运营内部系统，不需要过高并发）
 * 缓存：item_ranking_daily 预计算（凌晨聚合），接口层 10min 二级缓存
 */
@RestController
@RequestMapping("/api/ranking")
@RequiredArgsConstructor
@Tag(name = "排行榜", description = "TOP商品 / 热搜词 / TOP类目")
public class RankingController {

    private final RankingService rankingService;

    /**
     * TOP 商品排行榜
     *
     * 选品场景示例：
     *  ① 全平台 TOP10 GMV 商品（大促备货参考）
     *     GET /api/ranking/top-items?rankBy=gmv&days=7&limit=10
     *
     *  ② 女装类目 TOP20 销量（类目运营选款）
     *     GET /api/ranking/top-items?rankBy=pay_cnt&days=30&category=女装&limit=20
     *
     *  ③ 全品类 TOP50 浏览量（发现高流量低转化商品，针对性优化详情页）
     *     GET /api/ranking/top-items?rankBy=pv&days=7&limit=50
     *
     * 返回含：排名变化（↑↓新上榜）、商品名、品牌、GMV、销量、PV/UV、加购率、支付转化率
     */
    @Operation(summary = "TOP商品排行",
            description = "多维度商品排行。优先走预计算榜单(< 5ms)，无数据时实时聚合。10min 缓存。")
    @SentinelResource(value = "topItems", blockHandler = "blockFallback")
    @GetMapping("/top-items")
    public R<List<TopItemVO>> topItems(
            @Parameter(description = "排序维度: gmv(默认)/pay_cnt/pv/add_cart_cnt")
            @RequestParam(defaultValue = "gmv") String rankBy,
            @Parameter(description = "统计周期(天): 7(默认)/30/90")
            @RequestParam(defaultValue = "7") int days,
            @Parameter(description = "类目过滤，不传则全类目")
            @RequestParam(required = false) String category,
            @Parameter(description = "返回条数，最大100")
            @RequestParam(defaultValue = "10") int limit) {
        return R.ok(rankingService.topItems(rankBy, days, category, limit));
    }

    /**
     * 热搜词排行榜
     *
     * 选品场景示例：
     *  ① 今日实时热词（今天哪些词在爆发，运营立即跟进补货）
     *     GET /api/ranking/hot-keywords
     *
     *  ② 昨日热词（含完整 CTR/CVR 指标，今日实时暂无转化数据）
     *     GET /api/ranking/hot-keywords?date=2025-01-14&limit=20
     *
     *  ③ 趋势词识别：返回 trend=rising 的关键词，表示近 24h 搜索量相比昨日同期暴增 50%+
     *
     * 注意：实时数据来自 ClickHouse（秒级延迟），历史来自 MySQL（T+1 聚合）
     */
    @Operation(summary = "热搜词排行",
            description = "今日实时(CK)或历史日期(MySQL)热搜词，含 CTR/CVR 和趋势标记。")
    @SentinelResource(value = "hotKeywords", blockHandler = "blockFallback")
    @GetMapping("/hot-keywords")
    public R<List<HotKeywordVO>> hotKeywords(
            @Parameter(description = "查询日期 yyyy-MM-dd，不传或传 today 表示实时")
            @RequestParam(required = false) String date,
            @Parameter(description = "返回条数，最大50")
            @RequestParam(defaultValue = "20") int limit) {
        return R.ok(rankingService.hotKeywords(date, limit));
    }

    /**
     * TOP 类目排行
     *
     * 流量分配决策场景：
     *  - 近 30 天各类目 GMV 排行 + 占比，判断各品类体量
     *  - 结合 cartRate / payRate，找出"流量大转化差"的类目重点优化
     *
     * 场景示例：
     *   发现"男装"类目 GMV 排名第 3 但 payRate 只有 0.02（行业均值 0.05），
     *   说明商品详情/价格/评价有问题，安排运营重点整改
     */
    @Operation(summary = "TOP类目排行",
            description = "近 N 天各类目 GMV 排行，含 GMV 占比、加购率、支付转化率。")
    @SentinelResource(value = "topCategories", blockHandler = "blockFallback")
    @GetMapping("/top-categories")
    public R<List<CategoryStatVO>> topCategories(
            @Parameter(description = "统计周期(天): 7(默认)/30")
            @RequestParam(defaultValue = "7") int days,
            @Parameter(description = "返回类目数，最大20")
            @RequestParam(defaultValue = "10") int limit) {
        return R.ok(rankingService.topCategories(days, limit));
    }

    @SuppressWarnings("unused")
    public R<?> blockFallback(Object... args) {
        return R.fail(429, "请求过于频繁，稍后再试");
    }
}
