package com.ecom.analytics.search.sync;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * Canal FlatMessage 协议体 (Canal Server 推送到 Kafka 的标准 JSON 格式)
 *
 * 示例 (UPDATE 订单状态):
 * <pre>
 * {
 *   "data":[{"order_id":"1001","order_status":"1","paid_amount":"299.00","version":"2",...}],
 *   "old": [{"order_status":"0","version":"1"}],
 *   "database":"ecom_analytics",
 *   "table":"order_sync",
 *   "type":"UPDATE",
 *   "ts": 1700000000000,
 *   "es": 1699999999000,
 *   "pkNames": ["order_id"],
 *   "isDdl": false
 * }
 * </pre>
 *
 * 字段含义:
 *   data    - 变更后的行 (UPDATE/INSERT 是新值, DELETE 是删除前的值)
 *   old     - UPDATE 时变化前的字段值 (仅含 changed 字段, 用于增量推断)
 *   type    - INSERT / UPDATE / DELETE / QUERY / CREATE / ALTER / ...
 *   isDdl   - DDL 语句标志, 直接忽略 (我们只关心 DML)
 *   ts      - Canal 处理时间, es 是 binlog 事件时间
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class CanalFlatMessage {

    private String database;
    private String table;
    private String type;
    private Long ts;
    private Long es;
    private Boolean isDdl;
    private List<String> pkNames;
    private List<Map<String, String>> data;
    private List<Map<String, String>> old;
}
