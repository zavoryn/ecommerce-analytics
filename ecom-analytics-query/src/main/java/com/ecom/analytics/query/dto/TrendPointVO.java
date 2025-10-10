package com.ecom.analytics.query.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDate;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TrendPointVO implements Serializable {
    private LocalDate date;
    private Long pv;
    private Long uv;
    private Long payCnt;
}
