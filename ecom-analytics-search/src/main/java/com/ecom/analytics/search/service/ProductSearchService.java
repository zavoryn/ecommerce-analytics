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
import com.ecom.analytics.search.repository.doc.ProductDoc;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 商品搜索服务
 *
 * 核心设计（面试稿 1.5 ES 底层原理）:
 *
 * 1. 倒排索引：IK 分词后建立 Term Dictionary，查询时 O(len(word)) 定位
 *    磁盘 Block，远快于 MySQL LIKE '%连衣裙%' 的全表扫描。
 *
 * 2. 深分页：使用 search_after 游标而非 LIMIT OFFSET。
 *    LIMIT 10000,20 需要 ES 拉出前 10020 条再丢弃，I/O 浪费极大。
 *    search_after 只用最后一条的排序值作为游标，每次只拉 pageSize 条，
 *    无论翻到第几页性能恒定。（面试稿 2.7 跨表深分页问题的 ES 解法）
 *
 * 3. 多条件联合过滤（Roaring Bitmap 位运算，远快于跳表求交集）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProductSearchService {

    private final ElasticsearchClient esClient;

    /**
     * 带 search_after 游标的商品检索
     *
     * @param keyword     关键词（IK 分词 match）
     * @param category    类目过滤（精确 term）
     * @param minPrice    最低价
     * @param maxPrice    最高价
     * @param pageSize    每页大小
     * @param searchAfter 上一页游标（[sales30dValue, itemIdValue]），首页传 null
     */
    public SearchResult search(String keyword, String category,
                               Double minPrice, Double maxPrice,
                               int pageSize, List<String> searchAfter) throws IOException {

        BoolQuery.Builder bool = new BoolQuery.Builder();

        // 全文检索（IK 分词）
        if (keyword != null && !keyword.isEmpty()) {
            bool.must(Query.of(q -> q.match(m -> m.field("title").query(keyword))));
        }
        // 类目精确过滤
        if (category != null && !category.isEmpty()) {
            bool.filter(Query.of(q -> q.term(t -> t.field("category").value(category))));
        }
        // 价格区间
        if (minPrice != null || maxPrice != null) {
            bool.filter(Query.of(q -> q.range(r -> {
                r.field("price");
                if (minPrice != null) r.gte(JsonData.of(minPrice));
                if (maxPrice != null) r.lte(JsonData.of(maxPrice));
                return r;
            })));
        }
        // 只查在售商品
        bool.filter(Query.of(q -> q.term(t -> t.field("status").value(1))));

        SearchRequest.Builder req = new SearchRequest.Builder()
                .index("product_index")
                .query(Query.of(q -> q.bool(bool.build())))
                .size(pageSize)
                // 按 sales30d 降序，itemId 升序（排序唯一，search_after 游标稳定）
                .sort(s -> s.field(f -> f.field("sales30d").order(SortOrder.Desc)))
                .sort(s -> s.field(f -> f.field("itemId").order(SortOrder.Asc)));

        // search_after：使用 FieldValue 类型，ES 8.x Java Client 正确 API
        if (searchAfter != null && !searchAfter.isEmpty()) {
            List<FieldValue> fieldValues = searchAfter.stream()
                    .map(v -> {
                        // 尝试按数字解析（sales30d, itemId 都是数字）
                        try {
                            return FieldValue.of(Long.parseLong(v));
                        } catch (NumberFormatException e) {
                            return FieldValue.of(v);
                        }
                    })
                    .collect(Collectors.toList());
            req.searchAfter(fieldValues);
        }

        SearchResponse<ProductDoc> resp = esClient.search(req.build(), ProductDoc.class);
        List<ProductDoc> items = resp.hits().hits().stream()
                .map(Hit::source)
                .collect(Collectors.toList());

        // 取最后一条的 sort 值作为下一页游标（转 String，方便 HTTP 参数传递）
        List<String> nextSearchAfter = null;
        if (!resp.hits().hits().isEmpty()) {
            Hit<ProductDoc> last = resp.hits().hits().get(resp.hits().hits().size() - 1);
            nextSearchAfter = last.sort().stream()
                    .map(fv -> fv.isLong()   ? String.valueOf(fv.longValue())   :
                               fv.isDouble() ? String.valueOf(fv.doubleValue()) :
                               fv.stringValue())
                    .collect(Collectors.toList());
        }

        return new SearchResult(items, nextSearchAfter);
    }

    public record SearchResult(List<ProductDoc> items, List<String> nextSearchAfter) {}
}
