package com.ecom.analytics.common.response;

import com.ecom.analytics.common.exception.ErrorCode;
import lombok.Data;

import java.io.Serializable;

/**
 * 统一响应封装。
 *
 * 推荐使用 ErrorCode 重载, 避免裸 code/msg 数字泄露:
 *   R.fail(ErrorCode.PARAM_INVALID)               // 用枚举默认提示
 *   R.fail(ErrorCode.PARAM_INVALID, "uid required") // 自定义提示文本
 *
 * 兼容历史调用方仍可用 fail(int, String) / fail(String)。
 */
@Data
public class R<T> implements Serializable {

    private int code;
    private String msg;
    private T data;
    private long timestamp = System.currentTimeMillis();

    public static <T> R<T> ok() {
        return ok(null);
    }

    public static <T> R<T> ok(T data) {
        R<T> r = new R<>();
        r.code = ErrorCode.SUCCESS.getCode();
        r.msg = ErrorCode.SUCCESS.getDefaultMsg();
        r.data = data;
        return r;
    }

    public static <T> R<T> fail(int code, String msg) {
        R<T> r = new R<>();
        r.code = code;
        r.msg = msg;
        return r;
    }

    public static <T> R<T> fail(String msg) {
        return fail(ErrorCode.SYSTEM_ERROR.getCode(), msg);
    }

    public static <T> R<T> fail(ErrorCode errorCode) {
        return fail(errorCode.getCode(), errorCode.getDefaultMsg());
    }

    public static <T> R<T> fail(ErrorCode errorCode, String overrideMsg) {
        return fail(errorCode.getCode(), overrideMsg);
    }
}
