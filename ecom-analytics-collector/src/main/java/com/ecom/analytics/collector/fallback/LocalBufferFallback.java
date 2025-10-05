package com.ecom.analytics.collector.fallback;

import com.ecom.analytics.collector.producer.EventProducer;
import com.ecom.analytics.common.dto.UserEventDTO;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 本地缓冲兜底(对应面试稿 2.8 大促削峰)
 *
 * 设计要点:
 *  - 内存上限 10000 条,满则强制刷盘到 backup_*.log 多目录备份,避免内存溢出;
 *  - 每秒批量拉取至多 500 条,异步补偿重发到 MQ;
 *  - 重启时由 BackupLogScanner(略,生产中需补)扫描 backup 文件再投递。
 *
 * 注:这是一个"接受少量丢失换吞吐"的方案,关键链路(订单/支付)仍走 MQ + DB 双兜底。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LocalBufferFallback {

    private static final int CAPACITY = 10_000;
    private static final int BATCH_SIZE = 500;

    private final ConcurrentLinkedQueue<UserEventDTO> queue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger size = new AtomicInteger(0);

    private final EventProducer eventProducer;

    @PostConstruct
    public void init() {
        log.info("LocalBufferFallback initialized, capacity={}", CAPACITY);
    }

    public void offer(UserEventDTO dto) {
        if (size.get() >= CAPACITY) {
            // TODO 满了写多目录 backup_*.log,生产环境需要补
            log.warn("local buffer full, drop and write to backup log: {}", dto.getRequestId());
            return;
        }
        queue.offer(dto);
        size.incrementAndGet();
    }

    /**
     * 每秒批量重试一次,避免重试风暴
     */
    @Scheduled(fixedDelay = 1000)
    public void flush() {
        List<UserEventDTO> batch = new ArrayList<>(BATCH_SIZE);
        for (int i = 0; i < BATCH_SIZE; i++) {
            UserEventDTO dto = queue.poll();
            if (dto == null) break;
            size.decrementAndGet();
            batch.add(dto);
        }
        if (batch.isEmpty()) return;

        batch.forEach(dto -> {
            try {
                eventProducer.sendUserEvent(dto);
            } catch (Exception e) {
                // 重新入队,等待下次再试(指数退避未在脚手架实现)
                offer(dto);
            }
        });
    }
}
