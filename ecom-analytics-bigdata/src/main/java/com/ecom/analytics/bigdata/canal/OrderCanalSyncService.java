package com.ecom.analytics.bigdata.canal;

/**
 * Canal MySQL Binlog → ES 同步 (面试稿 2.7 追问: 跨月分表深分页解法)
 *
 * <h3>实现位置</h3>
 * 实际的 Kafka Listener + ES 写入实现在 search 模块:
 *   {@code com.ecom.analytics.search.sync.OrderCanalSyncListener}
 *
 * 把同步落到 search 模块的原因:
 *  1. search 模块已经持有 ElasticsearchOperations / OrderEventDoc, 避免跨模块循环依赖
 *  2. "搜索服务负责维护自己的索引"是更清晰的职责边界
 *  3. bigdata 模块定位是离线 Flink 作业容器, 不长期 run, 不适合放在线消费者
 *
 * <h3>整体链路</h3>
 * <pre>
 *  MySQL order_sync (核心交易)
 *        ↓  binlog (row 模式)
 *  Canal Server
 *        ↓  FlatMessage JSON → Kafka topic: ecom.canal.order_sync
 *  search 模块 OrderCanalSyncListener  (本类的实现入口)
 *        ↓  ES index/delete API (orderId 作 _id)
 *  Elasticsearch order_event_index
 *        ↓  search_after 游标
 *  运营后台 (彻底解放 MySQL)
 * </pre>
 *
 * <h3>开关</h3>
 *  CANAL_SYNC_ENABLED=true 启用; 默认关闭(开发/CI 无 Canal Server 时不影响应用启动)。
 *
 * <h3>部署</h3>
 * Canal Server 单独部署:
 * <pre>
 *  docker run -d --name canal-server \
 *    -e canal.instance.master.address=mysql:3306 \
 *    -e canal.mq.topic=ecom.canal.order_sync \
 *    -e canal.serverMode=kafka \
 *    canal/canal-server:v1.1.7
 * </pre>
 *
 * <h3>幂等 / 顺序</h3>
 *  - ES 用 orderId 作为 _id, 重放消息自动覆盖, 天然幂等
 *  - Kafka topic 用 orderId 作 partition key, 保证同订单事件按序到达
 *  - 配合 order_sync.version 字段, 取消 / 创建乱序也能被 OrderPersistService 乐观锁拦截
 *
 * @see com.ecom.analytics.search.sync.OrderCanalSyncListener (核心实现)
 * @see com.ecom.analytics.search.sync.CanalFlatMessage       (Canal 消息协议)
 */
public class OrderCanalSyncService {
    private OrderCanalSyncService() { }
}
