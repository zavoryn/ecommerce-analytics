package com.ecom.analytics.search.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.json.JsonData;
import com.ecom.analytics.search.repository.doc.OrderEventDoc;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 运营后台订单搜索服务（面试稿 2.7 跨月分表深分页核心解法）
 *
 * 【解决的核心问题】
 * 运营要查"1月15日，已支付，女装类目，金额>200元"的订单，并按时间倒序翻到第500页：
 *
 * MySQL 的问题：
 *  - order_sync 按月分表 → UNION ALL 6 张表 → 内存合并 10000 条 → 排序 → 取第500页 → OOM风险
 *  - LIMIT 500*20, 20 每次需从头扫 10020 条
 *
 * ES 的解法：
 *  ① Canal 实时同步 order_sync → order_event_index（延迟 < 1s）
 *  ② 多字段 AND 过滤走 Roaring Bitmap 位运算，毫秒级定位匹配文档集合
 *  ③ search_after 游标：传上一页最后一条的 [orderTime, orderId]
 *     → 只拉当前页 pageSize 条 → 无论第几页，性能恒定
 *
 * 【search_after vs LIMIT OFFSET 性能对比】
 *  LIMIT 10000,20：ES 每个分片拉出 10020 条 → 汇总 → 丢弃前 10000 → 返回 20 条
 *                   随页数线性增长，翻到深页极慢且内存压力大
 *  search_after：  直接从游标处取 20 条，无论多深 I/O 恒定
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderSearchService {

    private final ElasticsearchClient esClient;

    /**
     * 运营后台订单搜索（多条件过滤 + search_after 深分页）
     *
     * @param userId      用户ID过滤（null=不过滤）
     * @param category    类目过滤（null=不过滤）
     * @param orderStatus 订单状态 0待支付/1已支付/2已取消/3已退款（null=不过滤）
     * @param minAmount   最小订单金额（null=不限）
     * @param maxAmount   最大订单金额（null=不限）
     * @param pageSize    每页大小
     * @param searchAfter 上一页游标 [orderTime毫秒值, orderId]，首页传 null
     */
    public SearchResult search(Long userId, String category, Integer orderStatus,
                               BigDecimal minAmount, BigDecimal maxAmount,
                               int pageSize, List<String> searchAfter) throws IOException {

        BoolQuery.Builder bool = new BoolQuery.Builder();

        // ── 精确过滤（走 Roaring Bitmap 位运算，毫秒级）────────────────────
        if (userId != null) {
            bool.filter(Query.of(q -> q.term(t -> t.field("userId").value(userId))));
        }
        if (StringUtils.hasText(category)) {
            bool.filter(Query.of(q -> q.term(t -> t.field("category").value(category))));
        }
        if (orderStatus != null) {
            bool.filter(Query.of(q -> q.term(t -> t.field("orderStatus").value(orderStatus))));
        }
        // ── 范围过滤 ────────────────────────────────────────────────────────
        if (minAmount != null || maxAmount != null) {
            bool.filter(Query.of(q -> q.range(r -> {
                r.field("orderAmount");
                if (minAmount != null) r.gte(JsonData.of(minAmount));
                if (maxAmount != null) r.lte(JsonData.of(maxAmount));
                return r;
            })));
        }

        SearchRequest.Builder req = new SearchRequest.Builder()
                .index("order_event_index")
                .query(Query.of(q -> q.bool(bool.build())))
                .size(pageSize)
                // 排序：orderTime 倒序（最新在前）+ orderId 升序（保证唯一，游标稳定）
                // 两个排序字段组合保证 search_after 游标在并发写入时不漂移
                .sort(s -> s.field(f -> f.field("orderTime").order(SortOrder.Desc)))
                .sort(s -> s.field(f -> f.field("orderId").order(SortOrder.Asc)));

        // ── search_after 游标（深分页核心，面试稿 1.5 / 2.7）──────────────
        if (searchAfter != null && !searchAfter.isEmpty()) {
            List<FieldValue> fv = searchAfter.stream()
                    .map(v -> {
                        try { return FieldValue.of(Long.parseLong(v)); }
                        catch (NumberFormatException e) { return FieldValue.of(v); }
                    })
                    .collect(Collectors.toList());
            req.searchAfter(fv);
        }

        SearchResponse<OrderEventDoc> resp = esClient.search(req.build(), OrderEventDoc.class);
        List<OrderEventDoc> items = resp.hits().hits().stream()
                .map(Hit::source)
                .collect(Collectors.toList());

        // 取最后一条的 sort 值作为下一页游标
        List<String> nextSearchAfter = null;
        if (!resp.hits().hits().isEmpty()) {
            Hit<OrderEventDoc> last = resp.hits().hits().get(resp.hits().hits().size() - 1);
            nextSearchAfter = last.sort().stream()
                    .map(fv -> fv.isLong()   ? String.valueOf(fv.longValue())   :
                               fv.isDouble() ? String.valueOf(fv.doubleValue()) :
                               fv.stringValue())
                    .collect(Collectors.toList());
        }

        return new SearchResult(items, nextSearchAfter, resp.hits().total() != null
                ? resp.hits().total().value() : 0L);
    }

    /**
     * 搜索结果（含总命中数，供前端显示"共 N 条"）
     * 注意：total 在 search_after 场景下只做参考（ES 默认 track_total_hits=10000）
     */
    public record SearchResult(
            List<OrderEventDoc> items,
            List<String> nextSearchAfter,
            long total) {}
}
