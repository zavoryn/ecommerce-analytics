package com.ecom.analytics.search.repository.doc;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 订单事件 ES 文档(供运营后台跨表深分页查询,面试稿 2.7 追问)
 *
 * 架构说明:
 *  MySQL 是核心交易数据库,不适合做复杂检索与深分页。
 *  通过 Canal 监听 MySQL binlog,实时同步到 ES。
 *  运营后台的明细检索(多条件 AND/OR、任意字段排序、深翻页)全走 ES,
 *  彻底解放 MySQL 连接资源保护核心交易链路。
 */
@Data
@Document(indexName = "order_event_index")
public class OrderEventDoc {

    @Id
    private Long orderId;

    @Field(type = FieldType.Long)
    private Long userId;

    @Field(type = FieldType.Long)
    private Long itemId;

    @Field(type = FieldType.Keyword)
    private String category;

    @Field(type = FieldType.Double)
    private BigDecimal orderAmount;

    /** 0-待支付 1-已支付 2-已取消 3-已退款 */
    @Field(type = FieldType.Integer)
    private Integer orderStatus;

    @Field(type = FieldType.Keyword)
    private String deviceId;

    @Field(type = FieldType.Date)
    private LocalDateTime orderTime;
}
