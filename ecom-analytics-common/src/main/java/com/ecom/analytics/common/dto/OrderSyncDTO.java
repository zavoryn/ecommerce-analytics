package com.ecom.analytics.common.dto;

import lombok.Data;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 订单系统同步过来的销售数据 DTO
 */
@Data
public class OrderSyncDTO implements Serializable {

    private Long orderId;
    private Long userId;
    private Long itemId;
    private String category;

    private Integer quantity;
    private BigDecimal orderAmount;
    private BigDecimal paidAmount;

    /** 订单状态:0-待支付 1-已支付 2-已取消 3-已退款 */
    private Integer orderStatus;

    /** 乐观锁版本号,用于补偿场景下识别最新数据 */
    private Long version;

    private LocalDateTime orderTime;
    private LocalDateTime updateTime;
}
