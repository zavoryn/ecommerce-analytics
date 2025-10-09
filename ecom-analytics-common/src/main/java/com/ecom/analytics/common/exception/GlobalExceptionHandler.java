package com.ecom.analytics.common.exception;

import com.ecom.analytics.common.response.R;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.stream.Collectors;

/**
 * 全局异常处理(Servlet 栈)。
 *
 * 设计要点:
 *   - 业务异常(BizException) 走 WARN 日志, 不打堆栈, 因为可预期
 *   - 参数异常 走 INFO 日志, 不污染监控
 *   - 系统异常 走 ERROR 日志 + 堆栈, 触发 ERROR 告警
 *   - 响应永远是 200 + R{ code, msg } 格式, 真正的 HTTP 状态码通过 @ResponseStatus 标识(便于负载均衡器统计)
 *
 * 不在本类中处理:
 *   - WebFlux 栈(Gateway) 由网关自己的 ErrorWebExceptionHandler 处理
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 业务可预期异常 - WARN, 不打堆栈 */
    @ExceptionHandler(BizException.class)
    @ResponseStatus(HttpStatus.OK)
    public R<?> handleBiz(BizException e, HttpServletRequest req) {
        log.warn("[BIZ] {} {} code={} msg={}", req.getMethod(), req.getRequestURI(),
                e.getCode(), e.getMessage());
        return R.fail(e.getCode(), e.getMessage());
    }

    /** @Valid 注解 + @RequestBody 校验失败 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public R<?> handleBodyValidation(MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ":" + fe.getDefaultMessage())
                .collect(Collectors.joining("; "));
        log.info("[VALID] body invalid - {}", detail);
        return R.fail(ErrorCode.PARAM_INVALID.getCode(), detail);
    }

    /** @Validated + @RequestParam 校验失败 */
    @ExceptionHandler(ConstraintViolationException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public R<?> handleParamValidation(ConstraintViolationException e) {
        String detail = e.getConstraintViolations().stream()
                .map(ConstraintViolation::getMessage)
                .collect(Collectors.joining("; "));
        log.info("[VALID] param invalid - {}", detail);
        return R.fail(ErrorCode.PARAM_INVALID.getCode(), detail);
    }

    /** 必填请求参数缺失 */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public R<?> handleMissingParam(MissingServletRequestParameterException e) {
        return R.fail(ErrorCode.PARAM_INVALID.getCode(),
                "缺少必填参数: " + e.getParameterName());
    }

    /** 参数类型不匹配(如把字符串传给 long) */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public R<?> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        return R.fail(ErrorCode.PARAM_INVALID.getCode(),
                "参数类型错误: " + e.getName() + "=" + e.getValue());
    }

    /** 请求体格式错误(JSON 解析失败) */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public R<?> handleBadJson(HttpMessageNotReadableException e) {
        log.info("[VALID] bad request body: {}", e.getMostSpecificCause().getMessage());
        return R.fail(ErrorCode.PARAM_INVALID.getCode(), "请求体格式错误");
    }

    /** HTTP 方法不支持 */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    @ResponseStatus(HttpStatus.METHOD_NOT_ALLOWED)
    public R<?> handleMethodNotAllowed(HttpRequestMethodNotSupportedException e) {
        return R.fail(ErrorCode.METHOD_NOT_ALLOWED.getCode(),
                "请求方法不允许: " + e.getMethod());
    }

    /** 兜底 - 所有未明确处理的异常 */
    @ExceptionHandler(Throwable.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public R<?> handleAll(Throwable e, HttpServletRequest req) {
        log.error("[SYS] uncaught exception at {} {}", req.getMethod(), req.getRequestURI(), e);
        return R.fail(ErrorCode.SYSTEM_ERROR.getCode(),
                ErrorCode.SYSTEM_ERROR.getDefaultMsg());
    }
}
