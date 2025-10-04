# 贡献指南

感谢你对 ecom-analytics 的关注！本文档说明如何参与贡献。

## 代码规范

- **Java 版本**：JDK 17，使用 Records、Text Blocks 等新特性
- **注释语言**：中文注释（面向中文开发者），类注释须说明设计思路和面试稿对应章节
- **命名规范**：
  - 类名 PascalCase，方法/变量 camelCase
  - 常量全大写下划线：`MAX_RETRY_COUNT`
  - MQ Topic 全大写：`TOPIC_USER_EVENT`
- **注释模板**（Service 层方法）：
  ```java
  /**
   * 方法功能一句话说明
   *
   * 设计要点（面试稿 X.X）：
   *  - 要点1
   *  - 要点2
   *
   * @param xxx 参数说明
   * @return 返回值说明
   */
  ```

## 分支策略

```
main          ← 稳定版本，不直接推送
  └── dev     ← 日常开发集成
        └── feature/xxx   ← 功能分支（从 dev checkout）
        └── fix/xxx       ← 问题修复
```

## 提交规范（Conventional Commits）

```
feat(collector): 新增批量埋点上报接口
fix(processor): 修复订单版本号乐观锁并发问题
docs(readme): 更新架构图
refactor(query): 重构趋势查询三段式逻辑
test(idempotent): 补充幂等服务单元测试
```

## 如何添加新功能

1. 从 `dev` 创建功能分支：`git checkout -b feature/my-feature`
2. 实现功能，补充注释（注明对应面试稿章节）
3. 更新 `docs/` 中相关文档
4. 更新 `docs/06-interview-notes.md` 对照表
5. 提交 PR 到 `dev`

## 待扩展方向（欢迎 PR）

- [ ] Flink 双流 Join 完整实现（替换 bigdata 模块的 TODO）
- [ ] Canal MySQL→ES 同步完整实现
- [ ] 数据对账报告邮件/飞书推送
- [ ] 单元测试覆盖（幂等/聚合/漏斗）
- [ ] Prometheus + Grafana 监控接入
- [ ] Hive ODS 层 DDL + Spark 聚合脚本
- [ ] 压测报告（JMeter / k6）
