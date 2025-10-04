package com.ecom.analytics.bigdata.kafka;

import com.ecom.analytics.common.dto.UserEventDTO;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

/**
 * 埋点数据 Kafka 高吞吐生产者
 *
 * 【架构说明】（面试稿 1.4.1 大厂标准链路）
 *
 * 为什么用 Kafka 而不是 RocketMQ 处理埋点？
 *   - Kafka：专为日志流设计，顺序写盘，单机吞吐量可达百万 QPS，
 *             双十一级别洪峰完全抗得住，消息保留 72h 可重放
 *   - RocketMQ：定位是业务消息（事务消息/延迟消息/死信），
 *                用于订单/支付等需要强可靠的场景
 *
 * 本项目架构：
 *   埋点数据 → Kafka（削峰）→ Flink（清洗）→ ClickHouse（OLAP）
 *   订单数据 → RocketMQ（可靠投递）→ Processor → MySQL
 *
 * 分区策略：按 device_id hash 分区
 *   → 同一设备的事件落同一分区 → Flink 分区内有序 → windowFunnel 步骤不乱序
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EventKafkaProducer {

    public static final String TOPIC = "ecom-user-events";

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    /**
     * 发送埋点事件
     * Key = device_id，保证同设备事件有序落同一 Partition
     */
    public void send(UserEventDTO dto) {
        try {
            String payload = objectMapper.writeValueAsString(dto);
            ProducerRecord<String, String> record =
                    new ProducerRecord<>(TOPIC, dto.getDeviceId(), payload);

            CompletableFuture<SendResult<String, String>> future =
                    kafkaTemplate.send(record);

            future.whenComplete((result, ex) -> {
                if (ex != null) {
                    log.error("Kafka send failed, requestId={}, falling back to RocketMQ",
                            dto.getRequestId(), ex);
                    // 降级：Kafka 失败时走 RocketMQ 兜底（两者并存）
                    // rocketMQFallback.send(dto);
                }
            });
        } catch (Exception e) {
            log.error("Kafka send error", e);
        }
    }
}
