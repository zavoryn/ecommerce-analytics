package com.ecom.analytics.collector.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Knife4j OpenAPI 文档配置 - collector 服务。
 *
 * 访问入口: http://localhost:8081/doc.html (Knife4j 增强 UI)
 *           http://localhost:8081/v3/api-docs   (OpenAPI 3 原始 JSON)
 *
 * 生产建议通过环境变量关闭: SPRINGDOC_API_DOCS_ENABLED=false
 */
@Configuration
@ConditionalOnProperty(name = "springdoc.api-docs.enabled", havingValue = "true", matchIfMissing = true)
public class OpenAPIConfig {

    @Bean
    public OpenAPI collectorOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("ecom-analytics-collector API")
                        .version("v1.0")
                        .description("数据采集服务: 接收前端埋点 / 订单同步 / 登录绑定 OneID")
                        .contact(new Contact().name("ecom-analytics").url("https://github.com/zavoryn/ecom-analytics")))
                .addSecurityItem(new SecurityRequirement().addList("Bearer"))
                .components(new Components()
                        .addSecuritySchemes("Bearer", new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("Authorization: Bearer <token>")));
    }
}
