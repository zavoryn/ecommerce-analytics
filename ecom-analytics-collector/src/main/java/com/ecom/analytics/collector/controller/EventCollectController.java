package com.ecom.analytics.collector.controller;

import com.ecom.analytics.collector.service.EventCollectService;
import com.ecom.analytics.common.dto.UserEventDTO;
import com.ecom.analytics.common.response.R;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 用户行为数据采集接口
 *
 * 设计要点(对应面试稿 2.2 / 2.6):
 *  1. 接口先写本地日志兜底,再发 MQ,避免直接同步落库导致接口超时;
 *  2. Redis 请求ID + 数据库唯一键做双重幂等;
 *  3. 大促时削峰填谷,本地 ConcurrentHashMap 缓存批量入库(见 LocalBufferFallback)。
 */
@RestController
@RequestMapping("/api/collect")
@RequiredArgsConstructor
@Tag(name = "数据采集", description = "前端埋点上报入口")
public class EventCollectController {

    private final EventCollectService eventCollectService;

    @Operation(summary = "上报用户行为事件")
    @PostMapping("/event")
    public R<Void> collectEvent(@Valid @RequestBody UserEventDTO dto) {
        eventCollectService.collect(dto);
        return R.ok();
    }

    @Operation(summary = "批量上报用户行为事件")
    @PostMapping("/event/batch")
    public R<Void> collectBatch(@RequestBody List<UserEventDTO> dtoList) {
        eventCollectService.collectBatch(dtoList);
        return R.ok();
    }
}
