package com.ecom.analytics.collector.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * OneID 登录绑定服务（面试稿 1.7：设备 ID + 用户 ID 双绑）
 *
 * 【核心逻辑】
 * 用户登录成功后，前端立即调用 /api/collect/login，将 device_id 与 user_id 关联写入
 * id_mapping 表。数仓侧每日凌晨用 Spark（或 Flink）做图计算，把同一 device_id 下
 * 所有匿名轨迹（userId=null 的埋点）归并到已登录用户名下，实现 One-ID 打通。
 *
 * 【幂等保证】
 * id_mapping 表上有 UNIQUE KEY uk_device_user(device_id, user_id)。
 * 使用 INSERT IGNORE，同一设备多次登录只写一次，不会抛异常也不会重复计数。
 *
 * 【为什么不走 MQ？】
 * id_mapping 是强一致写库，延迟不可接受；MQ 异步写会导致绑定延迟，
 * 用户登录后马上查询时 OneID 还没建立，漏斗数据归因出错。
 * 因此直接同步写库，接口 p99 < 20ms（单行写 + 主键命中）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LoginService {

    private final JdbcTemplate jdbcTemplate;

    /**
     * 登录事件绑定 device_id → user_id
     *
     * @param deviceId 设备指纹（未登录阶段的唯一标识）
     * @param userId   登录后的用户 ID
     * @param source   来源渠道：app / h5 / mini（小程序）
     */
    public void bind(String deviceId, Long userId, String source) {
        String sql = "INSERT IGNORE INTO id_mapping (device_id, user_id, bind_time, source) " +
                     "VALUES (?, ?, NOW(), ?)";
        try {
            int rows = jdbcTemplate.update(sql, deviceId, userId, source);
            if (rows > 0) {
                log.info("[OneID] new bind: deviceId={} userId={} source={}", deviceId, userId, source);
            } else {
                // INSERT IGNORE 命中 uk_device_user，表示已存在，正常情况
                log.debug("[OneID] already bound: deviceId={} userId={}", deviceId, userId);
            }
        } catch (DuplicateKeyException e) {
            // 理论上 INSERT IGNORE 不会触发此异常，防御性捕获
            log.warn("[OneID] duplicate key (unexpected): deviceId={} userId={}", deviceId, userId);
        }
    }
}
