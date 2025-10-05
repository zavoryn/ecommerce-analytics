package com.ecom.analytics.collector.controller;

import com.ecom.analytics.collector.service.LoginService;
import com.ecom.analytics.common.response.R;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * OneID 登录绑定接口（面试稿 1.7）
 *
 * 触发时机：
 *   - 用户登录成功回调（App 端 onLoginSuccess）
 *   - 第三方 OAuth 授权完成后
 *   - Token 刷新时（静默登录）
 *
 * 与 /api/collect/event 的区别：
 *   - event 接口走 MQ 异步写，允许丢失（有本地日志兜底）
 *   - login 接口直接同步写 MySQL，强一致保证绑定关系
 *
 * 面试追问点（1.7）：
 *   Q: 同一个 deviceId 登录了多个账号怎么处理？
 *   A: 数仓侧 Spark 图计算：device_id ─ user_idA, device_id ─ user_idB
 *      → 以最近活跃的 user_id 作为主身份（主账号），其余标记为关联账号。
 *      采集侧不判断，保存所有绑定关系，逻辑收敛在数仓。
 *
 *   Q: 未登录匿名埋点怎么回溯？
 *   A: event_detail 表记录了 device_id。绑定后，数仓 Spark Job 每日
 *      LEFT JOIN id_mapping WHERE event.device_id = mapping.device_id
 *      → 将 userId 回填到匿名事件，实现历史行为归因。
 */
@RestController
@RequestMapping("/api/collect")
@RequiredArgsConstructor
@Tag(name = "OneID 登录绑定", description = "用户登录时将 device_id 与 user_id 关联写入 id_mapping 表")
public class LoginController {

    private final LoginService loginService;

    @Operation(
        summary = "登录事件绑定",
        description = "登录成功后调用，将设备指纹与用户ID绑定到 id_mapping 表。" +
                      "接口幂等（同设备+用户重复调用无副作用）。"
    )
    @PostMapping("/login")
    public R<Void> login(@Valid @RequestBody LoginRequest req) {
        loginService.bind(req.getDeviceId(), req.getUserId(), req.getSource());
        return R.ok();
    }

    /**
     * 登录绑定请求体
     */
    @Data
    public static class LoginRequest {

        /** 设备指纹（客户端生成，App 启动时计算一次，存本地） */
        @NotBlank(message = "deviceId 不能为空")
        private String deviceId;

        /** 登录用户 ID（由认证中心下发的 JWT 中解析） */
        @NotNull(message = "userId 不能为空")
        private Long userId;

        /**
         * 来源渠道：app / h5 / mini
         * 用于区分不同端的同一用户行为，便于渠道归因分析
         */
        private String source = "app";
    }
}
