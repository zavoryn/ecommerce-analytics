package com.ecom.analytics.common.exception;

/**
 * 业务异常 - 表达可预期的业务失败(参数错 / 资源不存在 / 状态不合法等)。
 *
 * 抛出该异常会被 GlobalExceptionHandler 捕获并转为 R.fail(code, msg) 响应,
 * 日志级别为 WARN(不打堆栈), 避免业务异常污染 ERROR 监控大盘。
 *
 * 不要用 BizException 包装数据库 / MQ / 网络异常 - 这些走 SYSTEM_ERROR 路径,
 * 由全局兜底处理器统一记录 ERROR + 堆栈。
 */
public class BizException extends RuntimeException {

    private final int code;

    public BizException(ErrorCode errorCode) {
        super(errorCode.getDefaultMsg());
        this.code = errorCode.getCode();
    }

    public BizException(ErrorCode errorCode, String overrideMsg) {
        super(overrideMsg);
        this.code = errorCode.getCode();
    }

    public BizException(ErrorCode errorCode, String overrideMsg, Throwable cause) {
        super(overrideMsg, cause);
        this.code = errorCode.getCode();
    }

    public int getCode() {
        return code;
    }
}
