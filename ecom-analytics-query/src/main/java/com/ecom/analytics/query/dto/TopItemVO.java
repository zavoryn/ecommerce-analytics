package com.ecom.analytics.query.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * TOP 商品榜单数据（运营选品核心接口）
 *
 * 场景：
 *  - 运营选品：近 7 天按 GMV/销量 找潜力爆款，安排流量倾斜
 *  - 大促选品：按类目维度看各品类头部商品，规划坑位
 *  - 竞品对比：按品牌维度看哪些品牌商品表现好
 *
 * 数据来源：
 *  - GMV/pay_cnt：order_sync（精确，已支付口径）
 *  - pv/add_cart_cnt：event_agg_daily（行为聚合）
 *  - itemName/brand：product_info（商品目录）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TopItemVO implements Serializable {

    /** 当前排名 */
    private Integer rank;

    /** 昨日排名（null = 新上榜） */
    private Integer prevRank;

    /** 排名变化描述：↑3 / ↓2 / 新上榜 / 持平 */
    private String rankChange;

    /** 商品ID */
    private Long itemId;

    /** 商品标题 */
    private String itemName;

    /** 类目 */
    private String category;

    /** 品牌 */
    private String brand;

    /** 实付金额（已支付口径） */
    private BigDecimal payAmount;

    /** GMV（含未支付） */
    private BigDecimal gmv;

    /** 支付笔数 */
    private Long payCnt;

    /** 浏览量 PV */
    private Long pv;

    /** 独立访客 UV */
    private Long uv;

    /** 加购次数 */
    private Long addCartCnt;

    /** 加购转化率 = addCartCnt / pv */
    private Double cartRate;

    /** 支付转化率 = payCnt / uv */
    private Double payRate;
}
