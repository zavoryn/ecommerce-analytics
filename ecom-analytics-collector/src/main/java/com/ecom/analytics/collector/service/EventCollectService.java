package com.ecom.analytics.collector.service;

import com.ecom.analytics.collector.fallback.LocalBufferFallback;
import com.ecom.analytics.collector.producer.EventProducer;
import com.ecom.analytics.common.dto.UserEventDTO;
import com.ecom.analytics.common.metric.BusinessMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 数据采集服务
 *
 * 处理流程:
 *  1. 幂等校验(Redis requestId, 5min 过期);
 *  2. 写一份"接收日志"到 access.log(兜底:即便后续 MQ 失败,日志可被定时任务扫出补偿);
 *  3. 发送到 RocketMQ TOPIC_USER_EVENT;
 *  4. MQ 发送失败 -> 落地到 LocalBufferFallback,由 BufferFlushTask 批量补偿。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EventCollectService {

    /**
     * 专用 logger,通过 logback-spring.xml 配置滚动日志(每天/每 100MB 切割,保留 24 小时)
     */
    private static final Logger ACCESS_LOG = LoggerFactory.getLogger("EVENT_ACCESS");

    private final IdempotentService idempotentService;
    private final EventProducer eventProducer;
    private final LocalBufferFallback localBufferFallback;
    /** 使用 ObjectProvider 以避免 MeterRegistry 缺失时启动失败(单元测试场景) */
    private final ObjectProvider<BusinessMetrics> metricsProvider;

    public void collect(UserEventDTO dto) {
        if (!idempotentService.tryAcquire(dto.getRequestId())) {
            log.debug("duplicate request, skip. requestId={}", dto.getRequestId());
            recordMetric("dup");
            return;
        }

        ACCESS_LOG.info("{}", dto);

        try {
            eventProducer.sendUserEvent(dto);
            recordMetric("ok");
        } catch (Exception e) {
            log.warn("MQ send failed, fallback to local buffer. requestId={}, err={}",
                    dto.getRequestId(), e.getMessage());
            localBufferFallback.offer(dto);
            recordMetric("fail");
        }
    }

    public void collectBatch(List<UserEventDTO> dtoList) {
        if (dtoList == null || dtoList.isEmpty()) return;
        dtoList.forEach(this::collect);
    }

    private void recordMetric(String result) {
        BusinessMetrics metrics = metricsProvider.getIfAvailable();
        if (metrics != null) metrics.eventCollect(result);
    }
}
