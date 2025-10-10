package com.ecom.analytics.query.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 热搜词数据（运营选品 + 搜索优化场景）
 *
 * 场景：
 *  - 运营选品：发现近期热搜词（如"显瘦连衣裙"搜索量暴增），快速补货/备货
 *  - 搜索优化：CTR 低的热词（搜了没点）→ 优化搜索结果排序或补充相关商品
 *  - 内容运营：热搜词 = 用户关心什么，用于直播/短视频选题
 *
 * 数据来源：
 *  - T+1（历史）：search_keyword_daily（MySQL 聚合表）
 *  - 实时（今日）：ClickHouse events_local WHERE event_name='search' GROUP BY keyword
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HotKeywordVO implements Serializable {

    /** 热度排名 */
    private Integer rank;

    /** 关键词 */
    private String keyword;

    /** 搜索次数 */
    private Long searchCnt;

    /** 搜索人数（device_id 去重） */
    private Long uv;

    /** 搜索后点击商品次数 */
    private Long clickCnt;

    /** 搜索后支付次数 */
    private Long payCnt;

    /** 搜索带来的成交金额 */
    private BigDecimal payAmount;

    /** 点击率 CTR = clickCnt / searchCnt */
    private Double ctr;

    /** 支付转化率 CVR = payCnt / uv */
    private Double cvr;

    /** 趋势标记：rising（飙升）/ stable（稳定）/ falling（下降）/ new（新词） */
    private String trend;
}
