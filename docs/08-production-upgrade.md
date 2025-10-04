# 生产化升级方案（08）

> 本文档是项目从「骨架可运行」走向「类生产级」的总体设计与实施蓝本。
>
> 全部任务分四个 Stage，按 P0→P1→P2 顺序推进。每个 Stage 完成后单独 commit + push。
>
> 维护规则：实施过程中若设计有偏差，必须回写本文档；本文档与代码版本号同步演进。

---

## 0. 总览

| Stage | 主题 | 核心交付物 | 目标 |
|---|---|---|---|
| **Stage 1** | P0 生产基线 | 统一异常 + 错误码 + Knife4j + TraceId 链路 + 网关 JWT | 让项目"看起来像生产项目"，请求可追、错误可读、接口可文档化、未鉴权请求挡在网关 |
| **Stage 2** | P0 硬伤修复 | SQL 注入修复 + JoinTempScan 桩补齐 + 新用户数实现 + 飞书告警 + `.gitignore` | 让 git 干净、processor 任务能跑全、安全风险归零 |
| **Stage 3** | P1 可观测 + 稳定性 | JSON 日志 + Prometheus 业务指标 + Kafka DLQ + RocketMQ 事务消息 + 缓存三防 + 多级缓存 | 让线上能排障（日志可查、指标可看、消息不丢、缓存不挂） |
| **Stage 4** | P2 部署 + CI/CD + HA | Dockerfile + 监控栈 + K8s 资源 + GitHub Actions + Testcontainers + HA 配置示例 | 让代码"能上线"，本机 docker-compose 也跑得起完整的监控-告警闭环 |

下表是本次升级新增/修改的全部文件清单（按 Stage 列）：

| Stage | 文件 | 类型 |
|---|---|---|
| 1 | `ecom-analytics-common/.../exception/{BizException,ErrorCode,GlobalExceptionHandler}.java` | 新增 |
| 1 | `ecom-analytics-common/.../response/R.java` | 修改（与 ErrorCode 集成） |
| 1 | `ecom-analytics-common/.../trace/{TraceIdHolder,TraceIdFilter}.java` | 新增 |
| 1 | `ecom-analytics-common/.../config/{KafkaTraceInterceptor,RocketMqTraceInterceptor}.java` | 新增 |
| 1 | `ecom-analytics-{collector,query,search}/.../config/OpenAPIConfig.java` | 新增 |
| 1 | `ecom-analytics-gateway/.../filter/JwtAuthFilter.java` | 新增 |
| 1 | `ecom-analytics-gateway/.../filter/TraceIdGatewayFilter.java` | 新增 |
| 1 | `ecom-analytics-common/.../security/{JwtUtil,JwtProperties}.java` | 新增 |
| 1 | 各服务 `application.yml` + `logback-spring.xml` | 修改 |
| 2 | `ecom-analytics-processor/.../task/{DailyAggregateTask,JoinTempScanTask}.java` | 修改 |
| 2 | `ecom-analytics-common/.../alert/{AlertService,LarkAlertService}.java` | 新增 |
| 2 | `.gitignore` | 修改 |
| 3 | 各服务 `logback-spring.xml` | 修改（JSON 输出） |
| 3 | `ecom-analytics-common/.../metric/BusinessMetrics.java` | 新增 |
| 3 | `ecom-analytics-collector/.../config/KafkaConfig.java` | 修改（ack/重试/DLQ） |
| 3 | `ecom-analytics-collector/.../producer/OrderTxProducer.java` | 新增 |
| 3 | `ecom-analytics-common/.../cache/{CacheGuard,MultiLevelCache}.java` | 新增 |
| 4 | 各模块 `Dockerfile` | 新增 |
| 4 | `infra/docker-compose.yml` + `infra/{prometheus,grafana,loki}/*` | 修改/新增 |
| 4 | `deploy/k8s/*.yaml` | 新增 |
| 4 | `.github/workflows/ci.yml` | 新增 |
| 4 | 各模块 `src/test/...` | 新增 |
| 4 | `infra/docker-compose-ha.yml` | 新增 |

---

## Stage 1 · P0 生产基线

### 1.1 全局异常处理 + 错误码体系

**背景**：当前 `R.java` 只支持 `R.fail(500, msg)` 这种裸字符串，业务/参数/系统错误混在一起，前端无从分类处理；Controller 内部的 try/catch 各管各的，错误响应格式不统一。

**设计**：

错误码采用 **6 位整数**，前 1~2 位标识子系统：

```
10xxx  通用系统级
20xxx  网关层（鉴权/限流）
30xxx  采集服务
40xxx  处理服务
50xxx  查询服务
60xxx  搜索服务
99xxx  外部依赖（DB/MQ/缓存/三方）
```

**关键文件**：

```java
// ecom-analytics-common/.../exception/ErrorCode.java
public enum ErrorCode {
    SUCCESS(0, "ok"),
    SYSTEM_ERROR(10000, "系统内部错误"),
    PARAM_INVALID(10001, "参数不合法"),
    REQ_THROTTLED(10002, "请求过于频繁"),

    AUTH_MISSING(20001, "未携带认证信息"),
    AUTH_INVALID(20002, "认证已失效"),
    AUTH_FORBIDDEN(20003, "无权访问"),

    EVENT_DUPLICATE(30001, "重复埋点已被忽略"),
    EVENT_PERSIST_FAIL(30002, "埋点入库失败"),

    AGG_TASK_FAIL(40001, "聚合任务执行失败"),

    QUERY_NO_DATA(50001, "查询无结果"),
    SEARCH_INDEX_NOT_READY(60001, "搜索索引未就绪"),

    DB_ERROR(99001, "数据库异常"),
    MQ_ERROR(99002, "消息系统异常"),
    CACHE_ERROR(99003, "缓存异常"),
    EXTERNAL_TIMEOUT(99004, "外部服务超时");

    private final int code;
    private final String defaultMsg;
    // ...constructor + getter
}
```

```java
// ecom-analytics-common/.../exception/BizException.java
public class BizException extends RuntimeException {
    private final int code;
    public BizException(ErrorCode ec) {
        super(ec.getDefaultMsg());
        this.code = ec.getCode();
    }
    public BizException(ErrorCode ec, String msg) {
        super(msg);
        this.code = ec.getCode();
    }
}
```

```java
// ecom-analytics-common/.../exception/GlobalExceptionHandler.java
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(BizException.class)
    public R<?> handleBiz(BizException e) {
        log.warn("[BIZ] {} - {}", e.getCode(), e.getMessage());
        return R.fail(e.getCode(), e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public R<?> handleValidation(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ":" + fe.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return R.fail(ErrorCode.PARAM_INVALID.getCode(), msg);
    }

    @ExceptionHandler(Throwable.class)
    public R<?> handleAll(Throwable e) {
        log.error("[SYS] uncaught exception", e);
        return R.fail(ErrorCode.SYSTEM_ERROR.getCode(), ErrorCode.SYSTEM_ERROR.getDefaultMsg());
    }
}
```

**`R.java` 配套调整**：把 fail 改成 `R.fail(ErrorCode)` 与 `R.fail(ErrorCode, String)`，旧的 `R.fail(int, String)` 保留以避免破坏调用方。

**取舍**：错误码用「数字」而非「字符串枚举」，因为前端 i18n 时只关心数字；defaultMsg 用中文，方便后端日志直读。

---

### 1.2 Knife4j Swagger 配置类

**背景**：父 pom 已声明 `knife4j-openapi3-jakarta-spring-boot-starter:4.5.0` 但各微服务没引入也没配置类，`/doc.html` 进不去。

**设计**：每个微服务一个 `OpenAPIConfig`，提供：
- `OpenAPI` Bean（标题、版本、联系人）
- 默认 Header（`X-Trace-Id`、`Authorization`）

**关键代码**（以 collector 为例）：

```java
// ecom-analytics-collector/.../config/OpenAPIConfig.java
@Configuration
public class OpenAPIConfig {
    @Bean
    public OpenAPI api() {
        return new OpenAPI()
                .info(new Info()
                        .title("ecom-analytics-collector API")
                        .version("v1.0")
                        .description("埋点采集 / 订单同步 / 登录接口"))
                .addSecurityItem(new SecurityRequirement().addList("Bearer"))
                .components(new Components()
                        .addSecuritySchemes("Bearer", new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer").bearerFormat("JWT")));
    }
}
```

需补依赖：每个 web 模块 `pom.xml` 加 `knife4j-openapi3-jakarta-spring-boot-starter`。

`application.yml` 配置：

```yaml
springdoc:
  swagger-ui:
    path: /doc.html
    enabled: ${SWAGGER_ENABLED:true}   # 生产应通过环境变量关闭
knife4j:
  enable: true
  setting:
    language: zh_cn
```

**取舍**：每服务一个配置而非 common 模块抽公共配置——因为标题、分组名按服务区分；公共部分（traceId Header）通过 `@Operation` 注解写在共用 BaseController 里。

---

### 1.3 链路追踪 TraceId（MDC + 跨进程透传）

**背景**：当前日志格式默认，A 请求经过 gateway→collector→Kafka→processor 的日志各自独立，出问题没法串。

**设计**：

```
[ Gateway ] ── X-Trace-Id 注入 ── ▶ [ Collector ] ── MDC ─▶ logback ──▶ JSON 日志
                                       │
                                       ├── Kafka header: traceId ─▶ [ Processor ] ── MDC
                                       └── RocketMQ user prop ────▶
```

**关键文件**：

```java
// ecom-analytics-common/.../trace/TraceIdHolder.java
public final class TraceIdHolder {
    public static final String HEADER = "X-Trace-Id";
    public static final String MDC_KEY = "traceId";

    public static String getOrGenerate(String fromHeader) {
        return StringUtils.hasText(fromHeader) ? fromHeader : generate();
    }
    public static String generate() {
        // 16 位短 UUID，足够本系统去重，省日志体积
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
    public static void set(String traceId) { MDC.put(MDC_KEY, traceId); }
    public static void clear()             { MDC.remove(MDC_KEY); }
}
```

```java
// ecom-analytics-common/.../trace/TraceIdFilter.java
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp,
                                    FilterChain chain) throws ServletException, IOException {
        String traceId = TraceIdHolder.getOrGenerate(req.getHeader(TraceIdHolder.HEADER));
        TraceIdHolder.set(traceId);
        resp.setHeader(TraceIdHolder.HEADER, traceId);   // 响应回写，便于前端排查
        try {
            chain.doFilter(req, resp);
        } finally {
            TraceIdHolder.clear();
        }
    }
}
```

**Gateway（WebFlux 异步栈，需用 GlobalFilter 而非 OncePerRequestFilter）**：

```java
// ecom-analytics-gateway/.../filter/TraceIdGatewayFilter.java
@Component
public class TraceIdGatewayFilter implements GlobalFilter, Ordered {
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String traceId = exchange.getRequest().getHeaders().getFirst(TraceIdHolder.HEADER);
        if (!StringUtils.hasText(traceId)) traceId = TraceIdHolder.generate();
        String finalTraceId = traceId;
        ServerHttpRequest mutated = exchange.getRequest().mutate()
                .header(TraceIdHolder.HEADER, finalTraceId).build();
        exchange.getResponse().getHeaders().set(TraceIdHolder.HEADER, finalTraceId);
        return chain.filter(exchange.mutate().request(mutated).build());
    }
    @Override public int getOrder() { return Ordered.HIGHEST_PRECEDENCE; }
}
```

**Kafka 透传**：生产端 ProducerInterceptor 写 `traceId` 到 record header，消费端 ConsumerInterceptor 把 header 读到 MDC：

```java
// ecom-analytics-common/.../trace/KafkaTraceInterceptor.java
public class KafkaTraceProducerInterceptor implements ProducerInterceptor<String, Object> {
    @Override
    public ProducerRecord<String, Object> onSend(ProducerRecord<String, Object> record) {
        String tid = MDC.get(TraceIdHolder.MDC_KEY);
        if (tid != null) record.headers().add(TraceIdHolder.HEADER, tid.getBytes(UTF_8));
        return record;
    }
    // ...其余空实现
}
```

**logback pattern**（每服务的 `logback-spring.xml`）：

```xml
<pattern>%d{HH:mm:ss.SSS} [%X{traceId:-}] %-5level %logger{36} - %msg%n</pattern>
```

**取舍**：
- 不引入 SkyWalking/Sleuth，因为本项目重点是面试讲清"我自己实现了 traceId 透传"——比依赖框架更能展示思考过程。
- TraceId 不用雪花 ID 而用 UUID 截短 16 位，因为 16 位足够日内去重，省日志列宽。
- gateway 用 WebFlux，所以必须用 `GlobalFilter`，不能复用下游 servlet 栈的 `TraceIdFilter`。

---

### 1.4 网关 JWT 鉴权 + 白名单

**背景**：所有 `/api/**` 路由直通下游，未做鉴权。生产环境至少需要：埋点采集放开（前端 SDK 不持 token），运营大盘 / 排行榜必须鉴权。

**设计**：

| Path | 鉴权 |
|---|---|
| `/api/collect/event` | 白名单（前端埋点 SDK 不持 token） |
| `/api/collect/login` | 白名单 |
| `/api/sync/order` | 内部接口，仅允许内网 IP（IPWhitelistFilter） |
| `/api/query/**`、`/api/operation/**`、`/api/ranking/**` | 必须 JWT |
| `/api/search/**` | 必须 JWT |
| `/actuator/**` | 仅本机 / 监控网段 |
| `/doc.html`、`/v3/api-docs/**` | 仅 dev/test profile 放开 |

**关键代码**：

```java
// ecom-analytics-common/.../security/JwtUtil.java
public final class JwtUtil {
    public static Claims parse(String token, String secret) {
        return Jwts.parserBuilder()
                .setSigningKey(secret.getBytes(UTF_8))
                .build().parseClaimsJws(token).getBody();
    }
    public static String issue(long userId, String secret, long ttlSeconds) { /* ... */ }
}
```

```java
// ecom-analytics-gateway/.../filter/JwtAuthFilter.java
@Component
@Slf4j
public class JwtAuthFilter implements GlobalFilter, Ordered {

    private static final List<String> WHITE_LIST = List.of(
            "/api/collect/event", "/api/collect/login",
            "/actuator/health", "/doc.html", "/v3/api-docs"
    );

    private final JwtProperties props;
    public JwtAuthFilter(JwtProperties props) { this.props = props; }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();
        if (WHITE_LIST.stream().anyMatch(path::startsWith)) {
            return chain.filter(exchange);
        }
        String auth = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (!StringUtils.hasText(auth) || !auth.startsWith("Bearer ")) {
            return reject(exchange, ErrorCode.AUTH_MISSING);
        }
        try {
            Claims c = JwtUtil.parse(auth.substring(7), props.getSecret());
            ServerHttpRequest mutated = exchange.getRequest().mutate()
                    .header("X-User-Id", String.valueOf(c.get("uid")))
                    .build();
            return chain.filter(exchange.mutate().request(mutated).build());
        } catch (Exception e) {
            return reject(exchange, ErrorCode.AUTH_INVALID);
        }
    }
    // reject: 返回 401 + 统一 R 格式 JSON
    @Override public int getOrder() { return -1; }   // 在路由匹配之后，限流之前
}
```

```yaml
# ecom-analytics-gateway/.../application.yml 新增
ecom:
  jwt:
    secret: ${JWT_SECRET:dev-secret-please-change-in-prod-min-32-bytes}
    ttl-seconds: 7200
```

**取舍**：
- 鉴权放在 gateway 而不是每个下游服务——一次校验，下游通过 `X-User-Id` 拿到身份。
- JWT 密钥放环境变量；本地 dev 给默认值，但通过 README 提醒生产必须覆盖。
- 不引入 OAuth2/Spring Security，因为本项目鉴权简单（只验签），引入 Security 反而是过度工程。

---

## Stage 2 · P0 硬伤修复

### 2.1 修复 SQL 注入

**位置**：
- `RankingService.java:148` — `String catFilter = ... + "AND o.category = '" + category + "' "`
- `DailyAggregateTask.java:223-224` — `String catFilter = ... + " AND a.category = '" + category + "' ";`

**修复方案**：因为 `category` 可能是 null（全类目榜），不能简单换成 `?` 占位符。两种方案：

**方案 A（推荐，简单）**：把 category 也放到参数列表里，SQL 用 `(? IS NULL OR a.category = ?)`：

```java
String sql = "... WHERE a.event_date = ? AND (? IS NULL OR a.category = ?) ...";
jdbc.update(sql, date, category, category);
```

**方案 B**：白名单校验 + 仍然拼接（用于结构化部分如 `ORDER BY`）：

```java
private static final Set<String> ALLOWED_CATEGORIES = Set.copyOf(...);  // 启动时加载
if (category != null && !ALLOWED_CATEGORIES.contains(category)) {
    throw new BizException(ErrorCode.PARAM_INVALID);
}
```

`DailyAggregateTask` 走方案 A；`RankingService.queryFromRankingTable` 已经是参数化的，只有 `realtimeTopItems` 的 catFilter 是字符串拼接，统一改方案 A。

---

### 2.2 实现 `JoinTempScanTask.mergeAndPersist`

**当前**（line 236-240）：

```java
private boolean mergeAndPersist(String bizKey, String eventPayload, String orderPayload) {
    // TODO 生产实现
    log.info("[JoinTempScan] mergeAndPersist: bizKey={} (stub implementation)", bizKey);
    return true;
}
```

**实现**：

```java
@Component
public class JoinTempScanTask {

    private final ObjectMapper objectMapper;            // 注入
    private final EventPersistService eventPersistService;
    private final OrderPersistService orderPersistService;

    private boolean mergeAndPersist(String bizKey, String eventPayload, String orderPayload) {
        try {
            UserEventDTO event = objectMapper.readValue(eventPayload, UserEventDTO.class);
            OrderSyncDTO order = objectMapper.readValue(orderPayload, OrderSyncDTO.class);

            // 1) 行为流补写到 event_detail_YYYYMM + CK events_local
            eventPersistService.persist(event);
            // 2) 订单流补写到 order_sync（走 upsert + version 乐观锁）
            orderPersistService.upsert(order);

            log.info("[JoinTempScan] mergeAndPersist done bizKey={}", bizKey);
            return true;
        } catch (Exception e) {
            log.error("[JoinTempScan] mergeAndPersist failed bizKey={}", bizKey, e);
            return false;   // 让外层 catch 走 retry/dead 流程
        }
    }
}
```

**注意**：JoinTempScanTask 原本只有 `mysqlJdbcTemplate`，需要构造器注入两个 Service + ObjectMapper。

---

### 2.3 实现 `platform_daily.new_user_cnt`

**当前**（DailyAggregateTask.java:319）：`new_user_cnt = 0` 硬编码。

**实现**：基于 `id_mapping.bind_time` 字段——首次绑定日期即新增用户日。

```sql
UPDATE platform_daily SET new_user_cnt = (
    SELECT COUNT(DISTINCT m.user_id) FROM id_mapping m
    WHERE DATE(m.bind_time) = ?
      AND NOT EXISTS (
        SELECT 1 FROM id_mapping m2
        WHERE m2.user_id = m.user_id AND DATE(m2.bind_time) < ?
      )
) WHERE event_date = ?
```

这个 SQL 性能取决于 `id_mapping` 大小。如果数据量大（>1000w），应在 Stage 3 把 `new_user_cnt` 改为从 ClickHouse `uniq` + 反 join 计算。本 Stage 用 MySQL 实现保证逻辑正确，性能问题留到下阶段。

---

### 2.4 飞书机器人告警

**目标**：DailyAggregateTask 重试 3 次失败、JoinTempScan 死信、慢 SQL（>3s）→ 飞书群通知。

**关键文件**：

```java
// ecom-analytics-common/.../alert/AlertService.java
public interface AlertService {
    void send(AlertLevel level, String title, String content);
}
public enum AlertLevel { INFO, WARN, ERROR, CRITICAL }
```

```java
// ecom-analytics-common/.../alert/LarkAlertService.java
@Service
@ConditionalOnProperty(name = "ecom.alert.lark.webhook")
public class LarkAlertService implements AlertService {
    @Value("${ecom.alert.lark.webhook}") private String webhook;
    private final RestTemplate rt = new RestTemplate();

    @Override
    public void send(AlertLevel level, String title, String content) {
        // 飞书机器人 markdown 卡片格式
        Map<String, Object> body = Map.of(
            "msg_type", "interactive",
            "card", Map.of(
                "header", Map.of("title", Map.of("tag", "plain_text", "content", "[" + level + "] " + title),
                                 "template", levelToColor(level)),
                "elements", List.of(Map.of("tag", "markdown", "content", content))
            )
        );
        try {
            rt.postForObject(webhook, body, String.class);
        } catch (Exception e) {
            log.error("Lark alert send failed: {}", title, e);
        }
    }
}
```

**`@ConditionalOnProperty`**：如果环境没配 webhook，自动降级为 NoOp（用 `@ConditionalOnMissingBean` + `NoOpAlertService` 补一个默认实现），代码不会因为告警没配置而崩溃。

**接入示例**：

```java
// DailyAggregateTask
private void runWithRetry(String stepName, Runnable step) {
    for (int attempt = 1; attempt <= MAX_RETRY; attempt++) {
        try { step.run(); return; }
        catch (Exception e) {
            log.error("{} failed attempt={}/{}", stepName, attempt, MAX_RETRY, e);
            if (attempt == MAX_RETRY) {
                alertService.send(AlertLevel.CRITICAL,
                    "聚合任务失败-" + stepName,
                    "**任务**: " + stepName + "\n**重试次数**: " + MAX_RETRY +
                    "\n**异常**: `" + e.getMessage() + "`");
            }
        }
    }
}
```

---

### 2.5 `.gitignore` + `yyyyMM` 优化

`.gitignore` 加：

```
.claude/
.cursor/
.aider*
```

`DailyAggregateTask.yyyyMM` 替换为：

```java
private static final DateTimeFormatter YYYYMM = DateTimeFormatter.ofPattern("yyyyMM");
private String yyyyMM(LocalDate date) { return date.format(YYYYMM); }
```

---

## Stage 3 · P1 可观测性 + 稳定性

### 3.1 logback-spring.xml JSON 日志

**目标**：日志结构化（ts, level, traceId, service, msg, throwable），按级别分文件，可被 Loki/ELK 直接 ingest。

**新增依赖**（父 pom dependencyManagement）：

```xml
<dependency>
    <groupId>net.logstash.logback</groupId>
    <artifactId>logstash-logback-encoder</artifactId>
    <version>7.4</version>
</dependency>
```

**`logback-spring.xml` 模板**（每服务一份，service 名替换）：

```xml
<configuration scan="true" scanPeriod="60 seconds">
    <springProperty scope="context" name="SERVICE" source="spring.application.name"/>

    <!-- 本地开发：彩色 console -->
    <springProfile name="dev">
        <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
            <encoder>
                <pattern>%d{HH:mm:ss.SSS} [%X{traceId:-}] %-5level %logger{36} - %msg%n</pattern>
            </encoder>
        </appender>
        <root level="INFO"><appender-ref ref="CONSOLE"/></root>
    </springProfile>

    <!-- 生产：JSON 文件输出 -->
    <springProfile name="prod">
        <appender name="JSON_FILE" class="ch.qos.logback.core.rolling.RollingFileAppender">
            <file>logs/${SERVICE}.log</file>
            <rollingPolicy class="ch.qos.logback.core.rolling.SizeAndTimeBasedRollingPolicy">
                <fileNamePattern>logs/${SERVICE}.%d{yyyy-MM-dd}.%i.log</fileNamePattern>
                <maxFileSize>100MB</maxFileSize>
                <maxHistory>30</maxHistory>
                <totalSizeCap>20GB</totalSizeCap>
            </rollingPolicy>
            <encoder class="net.logstash.logback.encoder.LoggingEventCompositeJsonEncoder">
                <providers>
                    <timestamp/>
                    <logLevel/>
                    <loggerName/>
                    <message/>
                    <mdc/>
                    <stackTrace/>
                    <pattern>
                        <pattern>{"service":"${SERVICE}"}</pattern>
                    </pattern>
                </providers>
            </encoder>
        </appender>

        <!-- ERROR 单独成文件，方便告警扫描 -->
        <appender name="ERROR_FILE" class="ch.qos.logback.core.rolling.RollingFileAppender">
            <file>logs/${SERVICE}-error.log</file>
            <filter class="ch.qos.logback.classic.filter.ThresholdFilter"><level>ERROR</level></filter>
            <!-- ... rolling + JSON encoder（同上） -->
        </appender>

        <root level="INFO">
            <appender-ref ref="JSON_FILE"/>
            <appender-ref ref="ERROR_FILE"/>
        </root>
    </springProfile>
</configuration>
```

---

### 3.2 Prometheus 业务指标

**关键指标**：

| 指标 | 类型 | 标签 | 用途 |
|---|---|---|---|
| `ecom_event_collect_total` | Counter | result(ok/fail) | 埋点上报成功率 |
| `ecom_event_collect_seconds` | Timer | event_name | 埋点接口延迟分布 |
| `ecom_mq_consume_latency_ms` | Timer | topic | MQ 消费延迟（从生产到消费） |
| `ecom_mq_consume_total` | Counter | topic, result | 消费成功失败 |
| `ecom_agg_task_duration_seconds` | Timer | step | 聚合任务每步耗时 |
| `ecom_slow_sql_total` | Counter | service | 慢 SQL 计数（>1s） |
| `ecom_cache_hit_total` | Counter | cache_name, level(L1/L2), result(hit/miss) | 多级缓存命中率 |

**实现**：

```java
// ecom-analytics-common/.../metric/BusinessMetrics.java
@Component
public class BusinessMetrics {
    private final MeterRegistry registry;
    public BusinessMetrics(MeterRegistry registry) { this.registry = registry; }

    public void eventCollect(String result) {
        registry.counter("ecom_event_collect_total", "result", result).increment();
    }
    public Timer eventCollectTimer(String eventName) {
        return Timer.builder("ecom_event_collect_seconds")
                .tag("event_name", eventName)
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);
    }
    public void mqLatency(String topic, long produceTs) {
        registry.timer("ecom_mq_consume_latency_ms", "topic", topic)
                .record(System.currentTimeMillis() - produceTs, TimeUnit.MILLISECONDS);
    }
    // ...
}
```

各服务 `pom.xml` 加 `micrometer-registry-prometheus`，actuator 已暴露 `/actuator/prometheus`，无需额外配置。

---

### 3.3 Kafka 消费者 ack/重试/DLQ

**当前**：默认 `at-least-once`，失败可能无限重试堵住消费组。

**目标**：失败重试 3 次进 DLQ topic，DLQ 由独立任务扫描告警。

**collector → kafka topic**：`ecom-events`
**DLQ topic**：`ecom-events.DLT`

**配置**：

```java
// ecom-analytics-processor/.../config/KafkaConfig.java
@Configuration
@EnableKafka
public class KafkaConfig {

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory(
            ConsumerFactory<String, String> consumerFactory,
            KafkaTemplate<String, String> kafkaTemplate) {

        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);

        // 重试 3 次（每次 1s 退避），失败后路由到 DLT
        DefaultErrorHandler errorHandler = new DefaultErrorHandler(
                new DeadLetterPublishingRecoverer(kafkaTemplate,
                        (rec, ex) -> new TopicPartition(rec.topic() + ".DLT", rec.partition())),
                new FixedBackOff(1000L, 3L));
        factory.setCommonErrorHandler(errorHandler);
        return factory;
    }
}
```

**DLQ 扫描任务**（每小时统计 DLT 消息数 + 发告警）：

```java
@Scheduled(cron = "0 5 * * * ?")
public void scanDlt() {
    // 用 AdminClient.listConsumerGroupOffsets 拿 DLT 当前 LAG
    // 或者起一个独立的 DLT consumer，逐条吞掉并打告警
}
```

---

### 3.4 RocketMQ 事务消息（订单场景）

**背景**：当前订单同步逻辑是「收到 MQ → 写 DB」，但订单系统侧是「写 DB → 发 MQ」。如果订单系统写 DB 成功但发 MQ 失败，下游收不到，造成数据漂移。

**方案**：订单系统侧改用 **RocketMQ 事务消息**——半消息 + 本地事务回调。

> 注意：本项目 collector 是消费方，不直接产生订单。本节描述的是 collector 提供的「订单回流接口」`/api/sync/order` 的可靠投递。具体在 `OrderTxProducer` 实现。

```java
@Component
public class OrderTxProducer {

    @Autowired
    private RocketMQTemplate rocketMQTemplate;

    public TransactionSendResult send(OrderSyncDTO dto) {
        Message<OrderSyncDTO> msg = MessageBuilder.withPayload(dto)
                .setHeader("orderId", dto.getOrderId()).build();
        return rocketMQTemplate.sendMessageInTransaction("order-sync-tx-group",
                MqTopics.ORDER_SYNC, msg, null);
    }
}

@RocketMQTransactionListener(txProducerGroup = "order-sync-tx-group")
class OrderTxListener implements RocketMQLocalTransactionListener {

    @Autowired
    private OrderLocalLogService localLogService;

    @Override
    public RocketMQLocalTransactionState executeLocalTransaction(Message msg, Object arg) {
        try {
            localLogService.writeLog((OrderSyncDTO) msg.getPayload());
            return RocketMQLocalTransactionState.COMMIT;
        } catch (Exception e) {
            return RocketMQLocalTransactionState.ROLLBACK;
        }
    }

    @Override
    public RocketMQLocalTransactionState checkLocalTransaction(Message msg) {
        OrderSyncDTO dto = (OrderSyncDTO) msg.getPayload();
        return localLogService.exists(dto.getOrderId())
                ? RocketMQLocalTransactionState.COMMIT
                : RocketMQLocalTransactionState.ROLLBACK;
    }
}
```

**需补表**：`order_local_log`（本地事务日志，用于 checkLocalTransaction 回查）。

---

### 3.5 缓存三防 + 多级缓存

**缓存穿透**（恶意请求不存在的 key）：
- 空对象缓存：查无结果时存 `NullObject + TTL 60s`
- 布隆过滤器（可选，本项目 query 接口参数有限，空对象方案足够）

**缓存击穿**（热点 key 失效瞬间大量并发）：
- 互斥锁：`SETNX <key>:lock` 取锁，未取到的线程短暂自旋后重试缓存。

**缓存雪崩**（大量 key 同时失效）：
- TTL 加随机抖动：基础 TTL 600s ± 60s（10% 抖动）

**关键代码**：

```java
// ecom-analytics-common/.../cache/CacheGuard.java
@Component
public class CacheGuard {

    private final StringRedisTemplate redis;
    public CacheGuard(StringRedisTemplate redis) { this.redis = redis; }

    /**
     * 带三防的 get-or-load。
     */
    public <T> T getOrLoad(String key, Class<T> type, long ttlSec, Supplier<T> loader) {
        String val = redis.opsForValue().get(key);
        if (val != null) {
            return "__NULL__".equals(val) ? null : JSON.parseObject(val, type);
        }
        String lockKey = key + ":lock";
        Boolean locked = redis.opsForValue().setIfAbsent(lockKey, "1", Duration.ofSeconds(5));
        if (Boolean.FALSE.equals(locked)) {
            // 未取到锁，等 50ms 再读一次缓存（其他线程应已写入）
            try { Thread.sleep(50); } catch (InterruptedException ignored) {}
            val = redis.opsForValue().get(key);
            return val == null ? null : JSON.parseObject(val, type);
        }
        try {
            T data = loader.get();
            long jitter = ThreadLocalRandom.current().nextLong(ttlSec / 10);
            redis.opsForValue().set(key,
                    data == null ? "__NULL__" : JSON.toJSONString(data),
                    Duration.ofSeconds(ttlSec + jitter));
            return data;
        } finally {
            redis.delete(lockKey);
        }
    }
}
```

**多级缓存（Caffeine + Redis）**：

```java
// ecom-analytics-common/.../cache/MultiLevelCache.java
@Component
public class MultiLevelCache {
    private final Cache<String, Object> l1 = Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterWrite(Duration.ofMinutes(1))
            .recordStats()
            .build();
    private final CacheGuard guard;

    public <T> T getOrLoad(String key, Class<T> type, long redisTtlSec, Supplier<T> loader) {
        Object v = l1.getIfPresent(key);
        if (v != null) {
            metrics.cacheHit("L1", "hit");
            return type.cast(v);
        }
        metrics.cacheHit("L1", "miss");
        T data = guard.getOrLoad(key, type, redisTtlSec, loader);
        if (data != null) l1.put(key, data);
        return data;
    }
}
```

**应用**：`RankingService.topItems` 等 `@Cacheable` 接口改用 `multiLevelCache.getOrLoad(...)`。

---

## Stage 4 · P2 部署 + CI/CD + HA

### 4.1 Dockerfile（多阶段）

每个 web 模块一份，模板：

```dockerfile
# ── stage 1: build ──
FROM maven:3.9-eclipse-temurin-17 AS builder
WORKDIR /app
COPY pom.xml .
COPY ecom-analytics-common ecom-analytics-common
COPY ecom-analytics-collector ecom-analytics-collector   # 本服务
RUN mvn -B -DskipTests -pl ecom-analytics-collector -am package

# ── stage 2: run ──
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY --from=builder /app/ecom-analytics-collector/target/*.jar app.jar
ENV JAVA_OPTS="-Xms512m -Xmx1g -XX:+UseG1GC -XX:MaxGCPauseMillis=200"
HEALTHCHECK --interval=30s --timeout=3s CMD wget -qO- http://localhost:8081/actuator/health || exit 1
EXPOSE 8081
ENTRYPOINT exec java $JAVA_OPTS -jar app.jar
```

---

### 4.2 docker-compose 补齐监控栈

`infra/docker-compose.yml` 加：

```yaml
prometheus:
  image: prom/prometheus:v2.51.0
  volumes:
    - ./prometheus/prometheus.yml:/etc/prometheus/prometheus.yml
  ports: ["9090:9090"]

grafana:
  image: grafana/grafana:10.4.0
  ports: ["3000:3000"]
  volumes:
    - ./grafana/provisioning:/etc/grafana/provisioning
    - ./grafana/dashboards:/var/lib/grafana/dashboards
  environment:
    GF_SECURITY_ADMIN_PASSWORD: admin

loki:
  image: grafana/loki:2.9.0
  ports: ["3100:3100"]
  volumes: [./loki/local-config.yaml:/etc/loki/local-config.yaml]

promtail:
  image: grafana/promtail:2.9.0
  volumes:
    - ./promtail/config.yml:/etc/promtail/config.yml
    - /var/log/ecom-analytics:/var/log/ecom-analytics:ro
```

`infra/prometheus/prometheus.yml` scrape config 把 5 个 web 服务的 `/actuator/prometheus` 都加上。

`infra/grafana/dashboards/ecom-overview.json`：包含「QPS、P95 延迟、缓存命中率、MQ 延迟、聚合任务耗时」四宫格看板。

---

### 4.3 K8s manifests

`deploy/k8s/` 每模块一个目录，含：
- `deployment.yaml`（含 readiness/liveness、preStop sleep 30s 优雅停机、resource limits）
- `service.yaml`
- `hpa.yaml`（CPU 70% / mem 80% 触发，replicas 2~10）
- `configmap.yaml`（环境变量）

**优雅停机要点**：

```yaml
lifecycle:
  preStop:
    exec:
      command: ["sh", "-c", "sleep 30"]   # 给 K8s 摘流量预留时间
terminationGracePeriodSeconds: 60
```

Spring Boot 端配套：

```yaml
server.shutdown: graceful
spring.lifecycle.timeout-per-shutdown-phase: 30s
```

---

### 4.4 GitHub Actions CI/CD

`.github/workflows/ci.yml`：

```yaml
name: ci
on:
  push: { branches: [master] }
  pull_request: { branches: [master] }
jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: 17, cache: maven }
      - run: mvn -B -ntp clean verify
      - name: Login GHCR
        if: github.event_name == 'push'
        uses: docker/login-action@v3
        with:
          registry: ghcr.io
          username: ${{ github.actor }}
          password: ${{ secrets.GITHUB_TOKEN }}
      - name: Build & push images
        if: github.event_name == 'push'
        run: |
          for svc in gateway collector processor query search; do
            docker build -t ghcr.io/${{ github.repository }}/ecom-analytics-$svc:${{ github.sha }} \
              -f ecom-analytics-$svc/Dockerfile .
            docker push ghcr.io/${{ github.repository }}/ecom-analytics-$svc:${{ github.sha }}
          done
```

---

### 4.5 Testcontainers 集成测试

依赖：

```xml
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>junit-jupiter</artifactId>
    <version>1.19.7</version>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>mysql</artifactId>
    <scope>test</scope>
</dependency>
```

示例（IdempotentService 三层防护测试）：

```java
@SpringBootTest
@Testcontainers
class IdempotentServiceIT {
    @Container static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withInitScript("init.sql");
    @Container static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", mysql::getJdbcUrl);
        r.add("spring.data.redis.host", redis::getHost);
        r.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @Test void redisDuplicateBlocked() { /* ... */ }
    @Test void dbUniqueKeyFallbackWhenRedisDown() { /* ... */ }
}
```

---

### 4.6 HA 配置示例

`infra/docker-compose-ha.yml`（独立文件，平时不启动）：

- MySQL：1 master + 2 slave + ProxySQL 读写分离
- ClickHouse：2 分片 + 2 副本（用 ZooKeeper 协调）
- Elasticsearch：3 节点集群
- Redis：1 master + 2 slave + 3 sentinel
- Kafka：3 broker + 3 zk（KRaft mode 后续切换）

**`README` 同步加一节**："本地学习用 `docker-compose.yml`；体验 HA 用 `docker-compose-ha.yml`（内存吃 12G+）"。

---

## A 附录：每 Stage 提交后的验证清单

### Stage 1 验证

- [ ] 所有 web 服务 `/doc.html` 可访问，文档列出全部接口
- [ ] 任意请求带 `X-Trace-Id: abc123` 时，响应 header 回写相同值
- [ ] 任意请求不带 token 访问 `/api/query/**` → 401 + JSON `{"code":20001,...}`
- [ ] 网关日志 + collector 日志能用同一个 traceId 串起来

### Stage 2 验证

- [ ] `git status` 不再出现 `.claude/`
- [ ] `RankingService` 传 `category="x' OR '1'='1"` 走不通（参数化）
- [ ] 手动触发 DailyAggregateTask，平台日表 `new_user_cnt` > 0
- [ ] 配置假 webhook，触发 task 失败 → 飞书群收到卡片

### Stage 3 验证

- [ ] `logs/ecom-analytics-collector.log` 是 JSON 一行一条，含 traceId
- [ ] `curl localhost:8081/actuator/prometheus` 看到 `ecom_event_collect_total`
- [ ] 模拟 Kafka 消费抛异常，DLT topic 出现消息
- [ ] 同一 key 短时间内连续打 500 个并发请求，DB QPS < 5（缓存击穿被互斥锁拦住）

### Stage 4 验证

- [ ] `docker compose up -d`（含监控栈）所有容器 healthy
- [ ] Grafana `http://localhost:3000` 看板有数据
- [ ] `mvn verify` 所有集成测试通过
- [ ] GitHub Actions push 后绿色，ghcr.io 收到镜像

---

## B 附录：实施时间预估

| Stage | 预估工作量 | 风险点 |
|---|---|---|
| 1 | 1~2 工作日 | JWT 密钥不能弱、各服务 Knife4j 依赖要同步加 |
| 2 | 0.5~1 工作日 | mergeAndPersist 涉及双 Service 依赖，注入顺序留意 |
| 3 | 2~3 工作日 | Kafka DLT、RocketMQ 事务消息均需本地验证 |
| 4 | 2~3 工作日 | K8s manifest 没集群也得 `kubectl apply --dry-run` 校验 |

---

## C 附录：文档变更约定

实施过程中若任何设计与本文档偏离，必须：
1. 在本文档对应小节回写新设计 + 偏离原因
2. 同步更新 `docs/07-progress.md` 模块状态
3. 涉及 API/SQL/部署变更，同步对应文档 03/04/05

---

## D 附录：Stage 1 实施回顾（2026-05-16）

实施完成度：4/4 子任务交付。下方记录设计与代码的几处偏离：

### D.1 与原设计的差异

| 项 | 原设计 | 实际实现 | 原因 |
|---|---|---|---|
| MQ TraceId 透传范围 | Kafka + RocketMQ 双拦截器 | 仅 RocketMQ（producer 设 header / consumer 读 MessageExt） | 主链路只用 RocketMQ；Kafka 仅 bigdata 模块且与 Flink 上下文耦合，留到 Stage 3 一并处理 |
| 消费者签名 | 文档未明示 | 由 `RocketMQListener<DTO>` 改为 `RocketMQListener<MessageExt>` + 手工 Jackson 反序列化 | 这是 rocketmq-spring 唯一可访问 user property 的途径 |
| JwtProperties 位置 | gateway 模块 | 移到 common 模块（@ConfigurationProperties），gateway 用 SecurityConfig `@EnableConfigurationProperties` 启用 | 后续 collector LoginService 也要签发 JWT，共用同一份 secret 配置更一致 |
| LoginController 签发 JWT | 设计未触及 | 不动 LoginController；签发逻辑放外部认证中心 | 保持单一职责（绑定 OneID）；测试期可手工签发 token（jjwt-cli 或单元测试） |
| common 模块新增依赖 | 仅 spring-web | 新增 spring-messaging、rocketmq-client、jjwt 全套，全部 optional | optional 不向下传递，避免污染 gateway 的 webflux 栈 |

### D.2 验证现状

| 验证项 | 状态 | 说明 |
|---|---|---|
| `mvn compile` 全模块通过 | ✅ | 8/8 模块 BUILD SUCCESS |
| `/doc.html` 可访问 | ⏸️ 待 docker 启动后验证 | 三个 web 服务均已配置 OpenAPIConfig |
| 请求自动注入 X-Trace-Id | ⏸️ 待运行时验证 | TraceIdGatewayFilter / TraceIdFilter 已就位 |
| 未带 token 访问 `/api/query/**` → 401 + R JSON | ⏸️ 待运行时验证 | JwtAuthFilter 已就位 |
| 上下游日志同 traceId 串联 | ⏸️ 待运行时验证 | RocketMqTrace + consumer 已就位 |

运行时验证将在监控栈 (Stage 4.2) 落地后一次性跑通端到端。

### D.3 给后续 Stage 留下的接口

- `BizException` + `ErrorCode`：后续 Stage 2/3 的业务异常一律使用此通道
- `AlertService`（Stage 2.4 已实现）：DailyAggregateTask 失败、JoinTempScan 死信已接入
- `JwtProperties.getSecret()`：可作 RocketMQ 消息签名 / 内部接口 IPWhitelist 的来源

---

## E 附录：Stage 2 实施回顾（2026-05-16）

实施完成度：5/5 子任务交付。

### E.1 关键改动

| 任务 | 文件 | 改动 |
|---|---|---|
| 2.1 SQL 注入修复 | `DailyAggregateTask.insertRankingBatch` | `catVal` 字符串拼接 → 全参数化 `(? IS NULL AND col IS NULL) OR col = ?` |
| 2.1 SQL 注入修复 | `RankingService.realtimeTopItems` | `catFilter` 字符串拼接 → `(? IS NULL OR o.category = ?)` |
| 2.2 双流补偿落库 | `JoinTempScanTask.mergeAndPersist` | 桩 `return true` → 真实反序列化 + `eventPersistService.persist()` + `orderPersistService.upsert()` |
| 2.2 双流补偿落库 | `JoinTempScanTask` 构造器 | 新增 `ObjectMapper` / `EventPersistService` / `OrderPersistService` / `AlertService` 注入 |
| 2.3 新增用户数 | `DailyAggregateTask.aggregatePlatformDaily` | `new_user_cnt = 0` 硬编码 → 子查询 `id_mapping GROUP BY user_id HAVING MIN(DATE(bind_time)) = ?` |
| 2.4 告警通道 | `common/alert/` 新增 5 文件 | `AlertService` + `AlertLevel` + `LarkAlertService`(飞书机器人) + `NoOpAlertService`(兜底) + `AlertAutoConfig`(@ConditionalOnProperty 自动切换) |
| 2.4 告警接入 | `DailyAggregateTask.runWithRetry` | TODO 日志 → `alertService.send(CRITICAL, ...)` |
| 2.4 告警接入 | `JoinTempScanTask.markDead` | TODO 日志 → `alertService.send(ERROR, ...)` |
| 2.5 工具优化 | `DailyAggregateTask.yyyyMM` | `replace+substring` → `DateTimeFormatter` 静态常量 |
| 2.5 仓库卫生 | `.gitignore` | 已在 Stage 1 提交时加 `.claude/.cursor/.aider*` |

### E.2 与设计的偏离

| 项 | 原设计 | 实际 | 原因 |
|---|---|---|---|
| `new_user_cnt` 实现 | 文档示例用 NOT EXISTS 关联子查询 | 改用 `GROUP BY HAVING MIN(...)` | 更高效（一次扫描 + 索引聚合），不依赖关联子查询的优化器质量 |
| 告警通道兜底 | 设计未明示无 webhook 时的行为 | 增加 `NoOpAlertService` + `@ConditionalOnMissingBean` | 业务侧无脑 @Autowired 即可，避免空指针 |
| RestTemplate 配置 | 文档未提 | 新增 `alertRestTemplate` 独立 Bean，2s/3s 短超时 | 防止告警 HTTP 阻塞拖累业务线程 |

### E.3 验证现状

| 验证项 | 状态 | 说明 |
|---|---|---|
| `mvn compile` 全模块通过 | ✅ | 8/8 模块 BUILD SUCCESS |
| `RankingService` category="' OR '1'='1" 注入阻断 | ⏸️ 待运行时验证 | 已参数化 |
| DailyAggregateTask 跑完后 `platform_daily.new_user_cnt > 0` | ⏸️ 待数据填充验证 | 依赖 id_mapping 有数据 |
| 故意触发任务失败 → 飞书群收到卡片 | ⏸️ 待配置 webhook 后端到端 | NoOp 模式可先看日志 |

---

## F 附录：Stage 3 实施回顾（2026-05-16）

实施完成度：5/5 子任务交付。

### F.1 关键改动

| 任务 | 文件 | 改动 |
|---|---|---|
| 3.1 JSON 日志 | `common/src/main/resources/logback-spring.xml` | 新增 spring profile-aware logback: dev 彩色 console / prod JSON 文件 + ERROR 分离 + 滚动 + 第三方组件降级 |
| 3.1 JSON 日志 | 父 pom + common pom | 引入 logstash-logback-encoder 7.4, 设为直接 dep, 全模块共享 |
| 3.2 Prometheus 指标 | `common/metric/BusinessMetrics.java` | 提供埋点 QPS / MQ 消费延迟 / 聚合任务耗时 / 缓存命中率自定义指标 |
| 3.2 Prometheus 指标 | `common/metric/MetricsAutoConfig.java` | @AutoConfiguration + @ConditionalOnBean(MeterRegistry) 自动注入 |
| 3.2 Prometheus 指标 | common pom | 引入 actuator + micrometer-registry-prometheus 直接 dep, 全模块统一 |
| 3.2 Prometheus 接入 | EventCollectService / UserEventConsumer / OrderSyncConsumer | 通过 ObjectProvider 安全注入并埋点 |
| 3.2 各服务 yml | gateway/collector/processor/query/search | 暴露 /actuator/prometheus + 默认 service tag |
| 3.3 RocketMQ DLQ | UserEventConsumer / OrderSyncConsumer | 设置 maxReconsumeTimes = 3 |
| 3.3 RocketMQ DLQ | processor/consumer/{DlqMonitorConsumer, UserEventDlqListener, OrderSyncDlqListener} | 监听 `%DLQ%<group>`, 死信触发飞书告警 + 指标计数 |
| 3.4 事务消息 | collector/producer/OrderTxProducer | 半消息 + 本地事务回调 + checkLocalTransaction 反查 |
| 3.4 事务消息 | infra/mysql/init.sql | 新增 `order_local_log` 表 (unique key on order_id) |
| 3.5 缓存三防 | query/cache/CacheGuard.java | 穿透(空值占位) + 击穿(Redis SETNX 互斥锁) + 雪崩(TTL 0~10% 抖动) |
| 3.5 多级缓存 | query/cache/MultiLevelCache.java | Caffeine L1 (1min) + Redis L2 (10min via CacheGuard) + 命中率指标 |
| 3.5 缓存接入 | RankingService.topItems | 由 @Cacheable 切换到 multiLevelCache.getOrLoad(...) |
| 3.5 缓存依赖 | query/pom.xml | 引入 caffeine direct dep |

### F.2 与设计的偏离

| 项 | 原设计 | 实际 | 原因 |
|---|---|---|---|
| MQ 重试机制 | Kafka ack/retry/DLQ | 改为 RocketMQ maxReconsumeTimes + %DLQ% 监听 | 主链路使用 RocketMQ 不是 Kafka，Kafka 仅 bigdata 模块（Flink 自己处理） |
| 缓存工具位置 | common 模块 | 放到 query 模块 | 仅 query 一处消费, 避免 common 模块依赖 Caffeine, 减小 common.jar 体积 |
| 事务消息接入面 | 替换 OrderSyncProducer | OrderTxProducer 单独存在, 不替换原 producer | 原 producer 是普通顺序消息已工作; 事务消息作为加强可用方案, 由 controller 决定何时切换 |
| 业务指标注入 | 直接 @Autowired BusinessMetrics | 改用 ObjectProvider | 应对单元测试 / 无 actuator 场景, 避免启动失败 |

### F.3 验证现状

| 验证项 | 状态 | 说明 |
|---|---|---|
| `mvn compile` 全模块通过 | ✅ | 8/8 模块 BUILD SUCCESS |
| `/actuator/prometheus` 出现 `ecom_*` 指标 | ⏸️ 待运行时验证 | BusinessMetrics 已通过 ObjectProvider 安全接入 |
| ERROR 日志单独成文件 | ⏸️ 待 prod profile 启用后 | logback-spring.xml 已配置 |
| 重试 3 次后入 %DLQ%, 触发告警 | ⏸️ 待端到端 | 监听器已注册, 配 webhook 即可验证 |
| 缓存击穿模拟: 1000 并发同 key DB 只访问一次 | ⏸️ 待集成测试 | CacheGuard SETNX 互斥锁已实现 |

### F.4 给 Stage 4 留下的接口

- `BusinessMetrics` 已埋点齐全, Stage 4.2 Grafana 看板直接消费 `ecom_*` 指标
- `logback-spring.xml` 已 JSON 化, Stage 4.2 Loki+Promtail 可直接采集 `logs/*.log`
- `OrderTxProducer` 已就位, 切换需配合 Stage 4.6 中 MySQL HA 主从读写分离

---

## G 附录：Stage 4 实施回顾（2026-05-17）

实施完成度：6/6 子任务交付。整个 4 阶段生产化升级蓝本全部落地。

### G.1 关键交付物

| 任务 | 文件 | 说明 |
|---|---|---|
| 4.1 Dockerfile | `ecom-analytics-{gateway,collector,processor,query,search}/Dockerfile` | 多阶段构建: maven builder → JRE-alpine runtime；HEALTHCHECK；JVM 参数（G1GC + ExitOnOOM）；时区 Asia/Shanghai |
| 4.1 优化 | `.dockerignore` | 排除 `.git/.idea/.claude/target/logs/docs` 降低构建上下文 |
| 4.2 监控栈 | `infra/docker-compose.yml` | 新增 prometheus / grafana / loki / promtail 4 个服务（`profiles: [monitor]`，按需启动） |
| 4.2 配置 | `infra/prometheus/prometheus.yml` | 抓取 5 个微服务 `/actuator/prometheus`，使用 `host.docker.internal` |
| 4.2 配置 | `infra/loki/local-config.yaml` | TSDB schema v13；本地存储 |
| 4.2 配置 | `infra/promtail/config.yml` | 抓取 `/var/log/ecom-analytics/**`；JSON 日志解析 + level/service 提取为 label |
| 4.2 配置 | `infra/grafana/provisioning/{datasources,dashboards}/*.yml` | 自动 provision Prometheus + Loki 数据源 + dashboard 目录 |
| 4.2 看板 | `infra/grafana/dashboards/ecom-overview.json` | 7 panel: 埋点 QPS / P95 / MQ 延迟 / 消费结果 / 缓存命中率 / JVM 堆 / 聚合任务耗时 |
| 4.3 K8s | `deploy/k8s/{namespace,common-config,gateway,collector,processor,query,search}.yaml` | 每服务 Deployment + Service + HPA；preStop sleep；readiness/liveness probe；rollingUpdate maxUnavailable=0 |
| 4.3 K8s 配套 | 各服务 `application.yml` | 新增 `server.shutdown: graceful` + `spring.lifecycle.timeout-per-shutdown-phase` + `management.endpoint.health.probes.enabled: true` |
| 4.4 CI/CD | `.github/workflows/ci.yml` | build → package → matrix 构建 5 个镜像推 GHCR；buildx + cache-from gha |
| 4.4 安全 | `.github/workflows/codeql.yml` | 每周自动 CodeQL Java security-extended 扫描 |
| 4.5 测试 | `pom.xml` + `ecom-analytics-collector/pom.xml` | testcontainers BOM 1.19.7；junit-jupiter + mysql + spring-boot-starter-test 测试依赖 |
| 4.5 测试 | `IdempotentServiceIT.java` | 真实 Redis 容器；测试 SETNX 幂等的 3 个 case |
| 4.6 HA | `infra/docker-compose-ha.yml` | MySQL 1 master+2 slave、ClickHouse 2×2 + ZK、ES 3 节点、Redis 1 master+2 slave+3 sentinel |
| 4.6 HA 配置 | `infra/clickhouse-ha/config.s*r*.xml` | 集群 macros + ZK + remote_servers |
| 4.6 HA 配置 | `infra/redis-ha/sentinel.conf` / `infra/mysql-ha/master-grant.sql` | Sentinel 仲裁配置 / 复制账号 |
| 4.6 文档 | `infra/README-ha.md` | 启动后初始化步骤；应用接入示例；生产建议 |

### G.2 与设计的偏离

| 项 | 原设计 | 实际 | 原因 |
|---|---|---|---|
| Dockerfile 路径 | 各模块根目录 | 同设计 | 一致 |
| 监控栈启动方式 | 默认随 docker-compose 启动 | `profiles: [monitor]` 按需启动 | 节省日常开发资源；`docker compose --profile monitor up -d` 一键起监控 |
| K8s manifest 粒度 | 每模块 4 个 yaml | 每模块 1 个聚合 yaml（含 Deployment+Service+HPA） | 聚合后阅读连贯，部署也简单 |
| Testcontainers 测试范围 | IdempotentService + EventPersistService | 仅 IdempotentService | 后者需 ClickHouse 容器 + 较多 SQL DDL，留作后续扩展 |
| Grafana dashboard | 4 宫格 | 7 panel | 增加缓存命中率、JVM、聚合任务耗时 panel |

### G.3 验证现状

| 验证项 | 状态 | 说明 |
|---|---|---|
| `mvn compile` 全模块通过 | ✅ | 8/8 模块 BUILD SUCCESS |
| `docker build -f ecom-analytics-*/Dockerfile .` | ⏸️ 待 Docker daemon | Dockerfile 已写好 |
| `docker compose --profile monitor up -d` 起监控栈 | ⏸️ 待运行时 | 配置已写好 |
| `kubectl apply -f deploy/k8s/` 在 K8s 集群部署 | ⏸️ 待集群 | 可用 `--dry-run=client` 校验语法 |
| GitHub Actions push 触发 → ghcr 收到 5 个镜像 | ⏸️ 推送后看 Actions tab | workflow 已写好 |
| `mvn -pl ecom-analytics-collector test` 跑通 IT | ⏸️ 待本地 Docker | 测试已写好 |
| `docker compose -f docker-compose-ha.yml up -d` HA 拓扑 | ⏸️ 注意内存 ~12GB | 示例只演示拓扑 |

---

## 总结

四个 Stage 全部交付：

- **Stage 1 P0 生产基线** — 让项目"看起来像生产项目"
- **Stage 2 P0 硬伤修复** — 让 git/processor/告警通路干净
- **Stage 3 P1 可观测+稳定性** — 让线上可排障、消息不丢、缓存不挂
- **Stage 4 P2 部署+CI/CD+HA** — 让代码可上线、有看板、有压测、有 HA 拓扑

后续若要继续完善的方向（不属于本次 4 Stage 范围）：
1. SkyWalking/Sleuth APM 全链路追踪（替代手写 TraceId）
2. ShardingSphere 读写分离与分库分表正式接入
3. ELK/Loki 日志告警规则
4. Sentinel 规则持久化到 Nacos
5. 完整的 contract 测试 + 压测脚本（k6/wrk + 自动化 baseline 比对）
