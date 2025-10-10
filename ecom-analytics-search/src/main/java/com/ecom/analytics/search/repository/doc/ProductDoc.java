package com.ecom.analytics.search.repository.doc;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 商品 ES 文档
 *
 * 设计对应面试稿 1.5(ES 倒排索引 vs MySQL LIKE):
 *  - title 使用 IK 中文分词器,支持 "夏季修身连衣裙" -> ["夏季","修身","连衣裙"] 分词检索;
 *  - 底层 Lucene 通过 FST 词典 + FOR 压缩倒排表 + Roaring Bitmap 联合查询,
 *    千万 SKU 的全文检索依然毫秒级;
 *  - 深分页用 search_after(见 ProductSearchService),避免 LIMIT 10000,20 的性能灾难。
 */
@Data
@Document(indexName = "product_index")
public class ProductDoc {

    @Id
    private Long itemId;

    /** IK 中文分词,支持"连衣裙"等模糊语义检索 */
    @Field(type = FieldType.Text, analyzer = "ik_max_word", searchAnalyzer = "ik_smart")
    private String title;

    /** keyword 字段:精确过滤/聚合,不分词 */
    @Field(type = FieldType.Keyword)
    private String category;

    @Field(type = FieldType.Keyword)
    private String brand;

    @Field(type = FieldType.Double)
    private BigDecimal price;

    /** 上架状态:1-在售 0-下架 */
    @Field(type = FieldType.Integer)
    private Integer status;

    @Field(type = FieldType.Long)
    private Long sales30d;

    @Field(type = FieldType.Date)
    private LocalDateTime createTime;

    @Field(type = FieldType.Date)
    private LocalDateTime updateTime;
}
