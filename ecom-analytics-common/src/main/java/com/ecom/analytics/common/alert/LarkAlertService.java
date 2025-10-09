package com.ecom.analytics.common.alert;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

/**
 * 飞书机器人告警实现。
 *
 * 配置:
 *   ecom:
 *     alert:
 *       lark:
 *         webhook: https://open.feishu.cn/open-apis/bot/v2/hook/<token>
 *
 * 卡片设计:
 *   - 标题 = [LEVEL] title, 颜色按 level 区分
 *   - 正文 markdown 块, 支持代码块 / 加粗
 *
 * 失败兜底: 内部 catch 全部异常, 仅打 error 日志, 不让告警把业务拖挂。
 */
@Slf4j
public class LarkAlertService implements AlertService {

    private final String webhook;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public LarkAlertService(String webhook, RestTemplate restTemplate, ObjectMapper objectMapper) {
        this.webhook = webhook;
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public void send(AlertLevel level, String title, String content) {
        try {
            Map<String, Object> body = Map.of(
                    "msg_type", "interactive",
                    "card", Map.of(
                            "config", Map.of("wide_screen_mode", true),
                            "header", Map.of(
                                    "title", Map.of("tag", "plain_text",
                                            "content", "[" + level + "] " + title),
                                    "template", levelToColor(level)),
                            "elements", List.of(Map.of("tag", "markdown", "content", content))
                    )
            );

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<String> entity = new HttpEntity<>(objectMapper.writeValueAsString(body), headers);

            String resp = restTemplate.postForObject(webhook, entity, String.class);
            log.debug("[Lark-Alert] sent level={} title={} resp={}", level, title, resp);
        } catch (Exception e) {
            log.error("[Lark-Alert] send failed level={} title={}", level, title, e);
        }
    }

    /** Lark card header template name. 见 https://open.feishu.cn/document/uAjLw4CM/ukTMukTMukTM/feishu-cards/card-components/containers/card-header */
    private String levelToColor(AlertLevel level) {
        return switch (level) {
            case INFO     -> "blue";
            case WARN     -> "yellow";
            case ERROR    -> "orange";
            case CRITICAL -> "red";
        };
    }
}
