package com.ecom.analytics.bigdata.flink;

import com.ecom.analytics.bigdata.kafka.EventKafkaProducer;
import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.api.common.functions.FilterFunction;
import org.apache.flink.api.common.functions.MapFunction;
import org.apache.flink.api.common.serialization.SimpleStringSchema;
import org.apache.flink.connector.kafka.source.KafkaSource;
import org.apache.flink.connector.kafka.source.enumerator.initializer.OffsetsInitializer;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;

import java.time.Duration;

/**
 * Flink 埋点实时清洗作业骨架
 *
 * 【完整数据链路】（面试稿 1.4.1 大厂标准流转链路）
 *
 *  前端埋点
 *     ↓  POST /api/collect/event
 *  Collector（写本地日志兜底，发 Kafka）
 *     ↓  ecom-user-events topic
 *  [本 Flink 作业]
 *   ① 反序列化 JSON
 *   ② 清洗：过滤时间戳异常、过滤爬虫/作弊流量
 *   ③ 丰富：从维表补充商品类目、品牌信息
 *   ④ 双流Join：行为流 JOIN 订单流，产出宽表
 *   ⑤ Sink → ClickHouse events_local（明细）
 *            → ClickHouse dwd_user_order_action（宽表）
 *     ↓
 *  ClickHouse（OLAP 查询，漏斗/趋势/GMV 毫秒级出结果）
 *
 * 【独立部署】
 *   mvn package -pl ecom-analytics-bigdata -DskipTests
 *   flink run -c com.ecom.analytics.bigdata.flink.EventCleanJob \
 *       target/ecom-analytics-bigdata-1.0.0-SNAPSHOT.jar
 */
public class EventCleanJob {

    public static void main(String[] args) throws Exception {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();

        // Checkpoint：每 30s 做一次，保证 Kafka offset 提交 + CK 写入的 Exactly-Once
        env.enableCheckpointing(30_000);
        env.getCheckpointConfig().setMinPauseBetweenCheckpoints(5_000);

        // ── Source：从 Kafka 消费埋点事件 ─────────────────────────
        KafkaSource<String> source = KafkaSource.<String>builder()
                .setBootstrapServers("localhost:9092")
                .setTopics(EventKafkaProducer.TOPIC)
                .setGroupId("flink-event-cleaner")
                .setStartingOffsets(OffsetsInitializer.earliest())
                .setValueOnlyDeserializer(new SimpleStringSchema())
                .build();

        DataStream<String> rawStream = env.fromSource(
                source,
                // Watermark：允许 5s 乱序（网络抖动），用事件时间做 windowFunnel
                WatermarkStrategy.forBoundedOutOfOrderness(Duration.ofSeconds(5)),
                "kafka-user-events"
        );

        // ── Transform 1：清洗过滤 ────────────────────────────────
        DataStream<String> cleanStream = rawStream
                .filter(new AntiSpamFilter())    // 过滤作弊/爬虫
                .map(new TimestampNormalizer()); // 修正异常时间戳（面试稿 2.9 时间戳错误）

        // ── Transform 2：双流 Join（行为 + 订单）────────────────
        // TODO: 连接订单 Kafka topic，按 (user_id, item_id) 在 2h 窗口内 JOIN
        // 产出 dwd_user_order_action 宽表写入 ClickHouse
        // 参考：面试稿 2.4 最终一致性方案的流式版本

        // ── Sink：写入 ClickHouse ────────────────────────────────
        // TODO: 接入 flink-connector-clickhouse 或 JDBC Sink
        // 建议每 1000 条或 1s 批量写入，避免小批量频繁写 Part 导致 merge 压力

        cleanStream.print(); // 脚手架先 print，替换成 CK Sink

        env.execute("ecom-analytics-event-clean-job");
    }

    /**
     * 反作弊过滤器
     * 真实场景：过滤机器人 UA、异常高频设备（1秒内同 device_id 超过 N 次）
     */
    static class AntiSpamFilter implements FilterFunction<String> {
        @Override
        public boolean filter(String value) {
            if (value == null || value.isEmpty()) return false;
            // TODO: 接入规则引擎，过滤异常 device_id
            return true;
        }
    }

    /**
     * 时间戳标准化（面试稿 2.9：订单时间戳错误排查）
     * 校验：事件时间不能早于 30 天前，不能晚于当前时间 + 5min（容忍客户端时钟误差）
     */
    static class TimestampNormalizer implements MapFunction<String, String> {
        @Override
        public String map(String value) {
            // TODO: 解析 JSON，校验 timestamp 字段，超出范围用服务端时间替换并打标
            return value;
        }
    }
}
