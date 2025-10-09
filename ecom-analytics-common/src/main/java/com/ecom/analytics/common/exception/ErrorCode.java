package com.ecom.analytics.common.exception;

/**
 * 统一错误码（6 位整数）。
 *
 * 编码段位:
 *   0       成功
 *   10xxx   通用系统级
 *   20xxx   网关层(鉴权 / 限流)
 *   30xxx   采集服务
 *   40xxx   处理服务
 *   50xxx   查询服务
 *   60xxx   搜索服务
 *   99xxx   外部依赖(DB / MQ / 缓存 / 三方)
 *
 * 新增错误码时:
 *   1) 保持段位规则
 *   2) defaultMsg 用面向运营 / 前端可读的中文短句
 *   3) 与 docs/04-api-guide.md 错误码表同步
 */
public enum ErrorCode {

    SUCCESS(0, "ok"),

    SYSTEM_ERROR(10000, "系统内部错误"),
    PARAM_INVALID(10001, "参数不合法"),
    REQ_THROTTLED(10002, "请求过于频繁"),
    METHOD_NOT_ALLOWED(10003, "请求方法不允许"),

    AUTH_MISSING(20001, "未携带认证信息"),
    AUTH_INVALID(20002, "认证已失效"),
    AUTH_FORBIDDEN(20003, "无权访问"),

    EVENT_DUPLICATE(30001, "重复埋点已被忽略"),
    EVENT_PERSIST_FAIL(30002, "埋点入库失败"),
    ORDER_SYNC_FAIL(30003, "订单同步失败"),

    AGG_TASK_FAIL(40001, "聚合任务执行失败"),
    JOIN_COMPENSATE_FAIL(40002, "双流补偿失败"),

    QUERY_NO_DATA(50001, "查询无结果"),
    QUERY_RANGE_TOO_LARGE(50002, "查询时间窗口超限"),

    SEARCH_INDEX_NOT_READY(60001, "搜索索引未就绪"),
    SEARCH_KEYWORD_BLANK(60002, "搜索关键词不能为空"),

    DB_ERROR(99001, "数据库异常"),
    MQ_ERROR(99002, "消息系统异常"),
    CACHE_ERROR(99003, "缓存异常"),
    EXTERNAL_TIMEOUT(99004, "外部服务超时");

    private final int code;
    private final String defaultMsg;

    ErrorCode(int code, String defaultMsg) {
        this.code = code;
        this.defaultMsg = defaultMsg;
    }

    public int getCode() {
        return code;
    }

    public String getDefaultMsg() {
        return defaultMsg;
    }
}
