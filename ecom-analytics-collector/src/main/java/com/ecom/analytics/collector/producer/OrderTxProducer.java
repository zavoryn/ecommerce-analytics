package com.ecom.analytics.collector.producer;

import com.ecom.analytics.common.constant.MqTopics;
import com.ecom.analytics.common.dto.OrderSyncDTO;
import com.ecom.analytics.common.trace.RocketMqTrace;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.LocalTransactionState;
import org.apache.rocketmq.spring.annotation.RocketMQTransactionListener;
import org.apache.rocketmq.spring.core.RocketMQLocalTransactionListener;
import org.apache.rocketmq.spring.core.RocketMQLocalTransactionState;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;

/**
 * 订单事务消息生产者 (面试稿 2.5 加强版)
 *
 * 解决问题: 订单系统侧 "写 DB 成功 + 发 MQ 失败" 的数据漂移问题
 *
 * RocketMQ 事务消息三步走:
 *   step 1: Producer 发送半消息(对消费者不可见, 进入 RMQ_SYS_TRANS_HALF_TOPIC)
 *   step 2: 执行本地事务: 写 order_local_log 表 (用 orderId 做主键, 唯一约束保证幂等)
 *           - 写成功 → 返回 COMMIT, Broker 让消费者可见
 *           - 写失败 → 返回 ROLLBACK, Broker 丢弃半消息
 *   step 3: 若 step 2 超时未回应, Broker 调用 checkLocalTransaction 反查
 *           - 表里有记录 → COMMIT
 *           - 表里没有 → ROLLBACK
 *
 * 对比 OrderSyncProducer:
 *   - 普通 producer 只有 "发了" / "没发" 两态, 网络抖动可能丢
 *   - 事务 producer 保证 "本地状态 ↔ MQ 状态" 强一致, 适合订单这种不能丢的场景
 *
 * 启用条件:
 *   依赖 order_local_log 表 (见 infra/mysql/init.sql)。
 *   未启用时 OrderSyncController 继续走 OrderSyncProducer (普通顺序消息), 不影响主流程。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderTxProducer {

    public static final String TX_GROUP = "order-sync-tx-group";

    private final RocketMQTemplate rocketMQTemplate;
    private final ObjectMapper objectMapper;

    /**
     * 发送事务消息。
     * arg 在 executeLocalTransaction 里可以拿到, 用于传递本地事务上下文。
     */
    public void send(OrderSyncDTO dto) {
        try {
            byte[] body = objectMapper.writeValueAsBytes(dto);
            Message<byte[]> msg = MessageBuilder.withPayload(body)
                    .setHeader("orderId", dto.getOrderId())
                    .setHeader(com.ecom.analytics.common.trace.TraceIdHolder.MQ_KEY,
                            com.ecom.analytics.common.trace.TraceIdHolder.currentOrGenerate())
                    .build();

            // rocketmq-spring 2.3.0 签名: (destination, message, arg)
            // txProducerGroup 通过 @RocketMQTransactionListener 注解绑定
            org.apache.rocketmq.client.producer.TransactionSendResult result =
                    rocketMQTemplate.sendMessageInTransaction(
                            MqTopics.TOPIC_ORDER_SYNC, msg, dto);

            log.info("[OrderTx] send tx msg orderId={} state={}", dto.getOrderId(), result.getLocalTransactionState());
        } catch (Exception e) {
            log.error("[OrderTx] send tx msg failed orderId={}", dto.getOrderId(), e);
            throw new RuntimeException(e);
        }
    }

    /**
     * 本地事务监听器 - executeLocalTransaction 写日志表; checkLocalTransaction 反查。
     */
    @RocketMQTransactionListener(rocketMQTemplateBeanName = "rocketMQTemplate")
    @RequiredArgsConstructor
    public static class OrderTxListener implements RocketMQLocalTransactionListener {

        private final JdbcTemplate jdbcTemplate;

        @Override
        public RocketMQLocalTransactionState executeLocalTransaction(Message msg, Object arg) {
            OrderSyncDTO dto = (OrderSyncDTO) arg;
            try {
                // INSERT 唯一约束兜底, 重发同 orderId 不会重复写
                jdbcTemplate.update(
                        "INSERT INTO order_local_log (order_id, status, created_at) VALUES (?, ?, NOW())",
                        dto.getOrderId(), "SENT");
                return RocketMQLocalTransactionState.COMMIT;
            } catch (DuplicateKeyException dup) {
                // 已存在视为成功, 幂等
                return RocketMQLocalTransactionState.COMMIT;
            } catch (Exception e) {
                log.error("[OrderTx] local tx failed orderId={}", dto.getOrderId(), e);
                return RocketMQLocalTransactionState.ROLLBACK;
            }
        }

        @Override
        public RocketMQLocalTransactionState checkLocalTransaction(Message msg) {
            // Broker 超时反查时, 通过 Header orderId 反查日志表
            Object orderIdHeader = msg.getHeaders().get("orderId");
            if (orderIdHeader == null) {
                return RocketMQLocalTransactionState.ROLLBACK;
            }
            long orderId = Long.parseLong(orderIdHeader.toString());
            Integer cnt = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM order_local_log WHERE order_id = ?",
                    Integer.class, orderId);
            return cnt != null && cnt > 0
                    ? RocketMQLocalTransactionState.COMMIT
                    : RocketMQLocalTransactionState.ROLLBACK;
        }
    }
}
