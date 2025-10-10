package com.ecom.analytics.search.controller;

import com.ecom.analytics.common.response.R;
import com.ecom.analytics.search.service.OrderSearchService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;

/**
 * 运营后台订单搜索接口（面试稿 2.7 跨月分表深分页解决方案）
 *
 * 场景：运营要查满足多条件的订单列表，并能翻到第 N 页（N 可能很大）。
 *
 * 为什么不走 MySQL？
 *  - order_sync 按月分表，多条件查询需要 UNION ALL 多张表
 *  - LIMIT 10000,20 需要读 10020 条丢弃前 10000，翻深页极慢且有 OOM 风险
 *  - 运营还要任意字段排序（如按 order_amount 降序），MySQL 跨表排序代价极大
 *
 * ES 方案：
 *  Canal 实时同步 MySQL order_sync → ES order_event_index（延迟 < 1s）
 *  → IK 分词支持按类目检索 + Roaring Bitmap 多条件 AND 过滤 + search_after 游标深分页
 *
 * 接口设计（对应 Gateway 路由 /api/search/**）：
 *  GET /api/search/order?orderStatus=1&category=女装&minAmount=200&pageSize=20
 *  翻页：传上一页返回的 searchAfter 值
 */
@RestController
@RequestMapping("/api/search")
@RequiredArgsConstructor
@Tag(name = "订单搜索", description = "运营后台订单多条件检索 + search_after 深分页（面试稿 2.7）")
public class OrderSearchController {

    private final OrderSearchService orderSearchService;

    /**
     * 运营后台订单搜索
     *
     * 典型 Query 参数示例：
     *   # 查已支付女装订单，金额 > 200，按时间倒序
     *   GET /api/search/order?orderStatus=1&category=女装&minAmount=200&pageSize=20
     *
     *   # 翻下一页（传上一页返回的 nextSearchAfter）
     *   GET /api/search/order?orderStatus=1&pageSize=20&searchAfter=1705296000000&searchAfter=666
     *
     * nextSearchAfter 说明：
     *   - 数组两个值：[orderTime毫秒时间戳, orderId]
     *   - 对应 ES 排序字段 [orderTime DESC, orderId ASC]
     *   - 保证翻页游标稳定（即使并发有新订单写入也不会跳记录）
     */
    @Operation(summary = "订单多条件搜索",
            description = "支持 orderStatus/category/userId/金额区间过滤 + search_after 深分页。" +
                    "首页不传 searchAfter，翻页传上一页返回的 nextSearchAfter。")
    @GetMapping("/order")
    public R<OrderSearchService.SearchResult> search(
            @Parameter(description = "用户ID，不传则不过滤")
            @RequestParam(required = false) Long userId,
            @Parameter(description = "商品类目，不传则不过滤")
            @RequestParam(required = false) String category,
            @Parameter(description = "订单状态：0待支付 1已支付 2已取消 3已退款，不传则不过滤")
            @RequestParam(required = false) Integer orderStatus,
            @Parameter(description = "最小订单金额")
            @RequestParam(required = false) BigDecimal minAmount,
            @Parameter(description = "最大订单金额")
            @RequestParam(required = false) BigDecimal maxAmount,
            @Parameter(description = "每页大小，最大50")
            @RequestParam(defaultValue = "20") int pageSize,
            @Parameter(description = "翻页游标 [orderTime毫秒, orderId]，首页不传")
            @RequestParam(required = false) List<String> searchAfter) throws IOException {

        int safePageSize = Math.min(pageSize, 50);
        return R.ok(orderSearchService.search(
                userId, category, orderStatus, minAmount, maxAmount, safePageSize, searchAfter));
    }
}
