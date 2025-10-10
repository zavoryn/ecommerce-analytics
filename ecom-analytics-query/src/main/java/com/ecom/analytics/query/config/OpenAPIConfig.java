package com.ecom.analytics.query.config;

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
 * Knife4j OpenAPI 文档配置 - query 服务。
 *
 * 访问入口: http://localhost:8083/doc.html
 *
 * 接口分组建议（在 application.yml 中配置 springdoc.group-configs）:
 *   trend     - 趋势查询
 *   funnel    - 漏斗分析
 *   operation - 运营大盘
 *   ranking   - 排行榜
 */
@Configuration
@ConditionalOnProperty(name = "springdoc.api-docs.enabled", havingValue = "true", matchIfMissing = true)
public class OpenAPIConfig {

    @Bean
    public OpenAPI queryOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("ecom-analytics-query API")
                        .version("v1.0")
                        .description("查询服务: 趋势 / 漏斗 / 运营大盘 / 排行榜，优先走聚合表 + Redis 缓存")
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
