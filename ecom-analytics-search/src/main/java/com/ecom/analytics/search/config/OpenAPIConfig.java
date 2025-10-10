package com.ecom.analytics.search.config;

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
 * Knife4j OpenAPI 文档配置 - search 服务。
 *
 * 访问入口: http://localhost:8084/doc.html
 */
@Configuration
@ConditionalOnProperty(name = "springdoc.api-docs.enabled", havingValue = "true", matchIfMissing = true)
public class OpenAPIConfig {

    @Bean
    public OpenAPI searchOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("ecom-analytics-search API")
                        .version("v1.0")
                        .description("搜索服务: ES 商品 / 订单检索, IK 分词 + search_after 深分页")
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
