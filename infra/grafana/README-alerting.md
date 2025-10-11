# Grafana 统一告警 (Unified Alerting)

## 设计目标

把 **指标层告警 (Prometheus)** 与 **日志层告警 (Loki)** 统一收口到 Grafana,
触发后推送同一个 contact point (飞书机器人), 与 Java 侧 `LarkAlertService` 形成双通道:

```
┌──────────────────┐  Java 侧业务告警  ┌──────────────┐
│ LarkAlertService │ ───────────────→ │ 飞书机器人群  │
└──────────────────┘                  └──────▲───────┘
                                              │
┌──────────────────┐  指标 / 日志告警 ─────────┘
│ Grafana Alerting │
│ ├─ Prometheus    │
│ └─ Loki          │
└──────────────────┘
```

## 文件结构

```
infra/grafana/provisioning/alerting/
├── contact-points.yaml   - 飞书 webhook contact point
├── policies.yaml         - 路由策略(分组 / 静默 / 重复间隔)
└── rules.yaml            - 告警规则定义
```

## 默认规则一览

### Prometheus 指标层

| 规则 | 表达式 | severity | 触发条件 |
|---|---|---|---|
| 埋点错误率 | `fail / total > 0.05` | error | 持续 5min |
| MQ 消费延迟 | `P95 > 5s` | warn | 持续 5min |
| 缓存命中率 | `hit / total < 0.8` | warn | 持续 10min |
| JVM 堆 | `used/max > 0.9` | critical | 持续 5min |

### Loki 日志层

| 规则 | 表达式 | severity | 触发条件 |
|---|---|---|---|
| ERROR 日志爆发 | `count {ERROR} > 10/min` | error | 持续 2min |
| DLQ 死信 | `count {DEAD_LETTER} > 0` | critical | 立即触发 |

## 启动方式

```bash
# 1. 设置飞书 webhook (必须)
export LARK_WEBHOOK="https://open.feishu.cn/open-apis/bot/v2/hook/<your-token>"

# 2. 起监控栈
cd infra
docker compose --profile monitor up -d

# 3. 验证规则已加载
# 浏览器打开 http://localhost:3000 → Alerts → Alert rules, 应看到 6 条规则
# Contact points 应看到 "lark-default"
```

## 调试技巧

- **手动触发**: Grafana Alerts → 找到规则 → 旁边的 ▶ 按钮 → "Preview alerts"
- **静默告警**: Grafana Alerts → Silences → New silence (按 label / matcher 静默)
- **历史告警**: Grafana Alerts → History (默认 30 天)

## 调整规则

- 编辑 `rules.yaml` → `docker compose restart grafana` → 规则重新加载
- 生产环境推荐通过 Grafana UI 改 + 导出 yaml 入版本控制, 而不是直接改 yaml(避免 UI 改动被 yaml 覆盖)

## 与 LarkAlertService 的分工

| 类型 | 谁负责 |
|---|---|
| 业务流程错误 (聚合失败 / 死信入库) | Java LarkAlertService (代码自爆) |
| 指标超阈值 (延迟 / 错误率 / 资源) | Grafana Alerting |
| 关键字日志爆发 | Grafana Loki ruler |

两个通道使用同一个飞书机器人 webhook, 但 Title / 内容格式不同, 一眼可分辨来源。
