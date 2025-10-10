package com.ecom.analytics.query.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class FunnelStepVO implements Serializable {
    private String step;
    private Long userCount;
    /** 相对于上一步的转化率(0~1) */
    private Double conversionRate;
}
