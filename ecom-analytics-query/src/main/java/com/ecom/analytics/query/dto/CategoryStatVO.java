package com.ecom.analytics.query.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 类目统计数据（运营大盘类目分析）
 *
 * 场景：
 *  - 类目 GMV 占比饼图：哪个类目贡献了多少 GMV
 *  - 类目流量对比：女装/男装/数码等各类目 PV/UV 比较
 *  - 类目转化漏斗：识别哪个类目加购率/支付率偏低，针对性优化
 *
 * 数据来源：category_daily 聚合表（由 DailyAggregateTask 每日填充）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CategoryStatVO implements Serializable {

    /** 类目名称 */
    private String category;

    /** 浏览量 */
    private Long pv;

    /** 独立访客数 */
    private Long uv;

    /** 搜索次数 */
    private Long searchCnt;

    /** 加购次数 */
    private Long addCartCnt;

    /** 创建订单次数 */
    private Long createOrderCnt;

    /** 支付笔数 */
    private Long payCnt;

    /** 实付金额 */
    private BigDecimal payAmount;

    /** GMV（含未支付） */
    private BigDecimal gmv;

    /** 有交易的 SKU 数 */
    private Long itemCnt;

    /** GMV 占全平台比例（百分比，如 0.23 = 23%） */
    private Double gmvRate;

    /** 加购率 = addCartCnt / pv */
    private Double cartRate;

    /** 支付转化率 = payCnt / uv */
    private Double payRate;
}
