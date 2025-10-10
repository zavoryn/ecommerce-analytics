package com.ecom.analytics.query.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 平台 GMV 趋势数据点（运营首页趋势折线图，一行 = 一天）
 *
 * 场景：运营看近 7/30/90 天 GMV 趋势，用于判断业务走势、大促效果复盘。
 * 数据来源：platform_daily（已聚合）→ 实时兜底：order_sync GROUP BY date
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GmvTrendPointVO implements Serializable {

    /** 日期 */
    private LocalDate date;

    /** 当日 GMV（实付，已支付订单） */
    private BigDecimal gmv;

    /** 当日订单数 */
    private Long orderCnt;

    /** 当日支付人数（付费 UV） */
    private Long payUserCnt;

    /** 当日全站 UV */
    private Long uv;

    /** 当日支付转化率 = payUserCnt / uv */
    private Double payRate;

    /** 当日退款金额 */
    private BigDecimal refundAmount;
}
