package com.ecom.analytics.collector.controller;

import com.ecom.analytics.collector.producer.OrderSyncProducer;
import com.ecom.analytics.common.dto.OrderSyncDTO;
import com.ecom.analytics.common.response.R;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 订单同步接口
 *
 * 入口形态有两种:
 *  1. 订单系统主动推送(本接口);
 *  2. 定时任务每 5min 反向拉取(见 OrderPullTask) —— 双重兜底,见面试稿 2.2。
 */
@RestController
@RequestMapping("/api/sync")
@RequiredArgsConstructor
@Tag(name = "订单同步", description = "订单系统推送销售数据入口")
public class OrderSyncController {

    private final OrderSyncProducer orderSyncProducer;

    @Operation(summary = "推送订单数据(订单系统主动调用)")
    @PostMapping("/order")
    public R<Void> push(@RequestBody List<OrderSyncDTO> orders) {
        orders.forEach(orderSyncProducer::send);
        return R.ok();
    }
}
