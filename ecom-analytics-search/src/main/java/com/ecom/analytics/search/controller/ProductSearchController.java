package com.ecom.analytics.search.controller;

import com.ecom.analytics.common.response.R;
import com.ecom.analytics.search.service.ProductSearchService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.List;

/**
 * 商品搜索接口
 *
 * 补足面试反馈"缺少搜索经验"的短板。
 * 演示要点:
 *  - 关键词搜索用 ES match(IK 分词),解决 MySQL LIKE 全表扫描问题;
 *  - 多条件 AND 过滤走 bool filter(Roaring Bitmap 位运算);
 *  - 深分页用 search_after 游标,传前一页最后一条的 [sales30d, itemId]。
 */
@RestController
@RequestMapping("/api/search")
@RequiredArgsConstructor
@Tag(name = "商品搜索", description = "ES 全文检索 + search_after 深分页")
public class ProductSearchController {

    private final ProductSearchService searchService;

    @Operation(summary = "搜索商品",
            description = "支持关键词全文检索 + 类目/价格过滤 + search_after 深分页。" +
                    "首页 searchAfter 传空,后续翻页传上一页返回的 nextSearchAfter")
    @GetMapping("/product")
    public R<ProductSearchService.SearchResult> search(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) Double minPrice,
            @RequestParam(required = false) Double maxPrice,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) List<String> searchAfter) throws IOException {

        return R.ok(searchService.search(keyword, category, minPrice, maxPrice, pageSize, searchAfter));
    }
}
