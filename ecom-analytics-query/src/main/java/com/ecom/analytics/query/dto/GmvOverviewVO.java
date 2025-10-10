package com.ecom.analytics.query.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 平台 GMV 大盘概览（运营首页核心指标卡片）
 *
 * 场景：运营人员打开数据大盘，首先看到今日实时数据和与昨日/上月同期对比。
 * 数据来源：order_sync 表（精确口径）+ events_local CK（UV实时）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GmvOverviewVO implements Serializable {

    // ──────────────── 今日实时 ────────────────
    /** 今日 GMV（实付金额，已支付订单） */
    private BigDecimal todayGmv;

    /** 今日订单数 */
    private Long todayOrderCnt;

    /** 今日支付人数（user_id 去重） */
    private Long todayPayUserCnt;

    /** 今日 UV（device_id 去重，从 ClickHouse 实时获取） */
    private Long todayUv;

    /** 今日下单转化率 = todayOrderCnt / todayUv */
    private Double todayConversionRate;

    // ──────────────── 昨日对比 ────────────────
    /** 昨日 GMV */
    private BigDecimal yesterdayGmv;

    /** 日环比增长率 = (todayGmv - yesterdayGmv) / yesterdayGmv，负数表示下跌 */
    private Double dayOnDayRate;

    // ──────────────── 本月累计 ────────────────
    /** 本月累计 GMV */
    private BigDecimal monthGmv;

    /** 本月累计订单数 */
    private Long monthOrderCnt;

    /** 本月退款金额 */
    private BigDecimal monthRefundAmount;

    /** 本月净 GMV = monthGmv - monthRefundAmount */
    private BigDecimal monthNetGmv;

    // ──────────────── 实时补充 ────────────────
    /** 今日平均客单价 = todayGmv / todayPayUserCnt */
    private BigDecimal todayArpu;
}
