package com.ecom.analytics.common.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serializable;
import java.util.Map;

/**
 * 前端埋点上报 DTO
 *
 * 设计参考大厂 DRD(数据需求文档)规范:
 *  - common 公共参数:解决 Who/When/Where
 *  - properties 业务属性:解决 What/How
 *
 * 未登录用户依靠 deviceId(设备指纹)做唯一标识,登录后由数仓 ID-Mapping
 * 将匿名 deviceId 轨迹归并到 userId 下(OneID)。
 */
@Data
public class UserEventDTO implements Serializable {

    /** 请求 ID,前端生成,用于幂等 */
    @NotBlank
    private String requestId;

    /** 设备指纹(未登录用户唯一凭证) */
    @NotBlank
    private String deviceId;

    /** 用户 ID,登录后才有 */
    private Long userId;

    /** 会话 ID(打开 App 到退出算一次 session,默认 30 分钟生命周期),用于漏斗串联 */
    @NotBlank
    private String sessionId;

    /** 事件名称,见 EventType */
    @NotBlank
    private String eventName;

    /** 客户端事件时间戳(毫秒) */
    @NotNull
    private Long timestamp;

    /** 操作系统 */
    private String os;
    /** App 版本 */
    private String appVersion;
    /** 网络类型 */
    private String network;

    /** 业务属性:item_id / category / price / page_source 等 */
    private Map<String, Object> properties;
}
