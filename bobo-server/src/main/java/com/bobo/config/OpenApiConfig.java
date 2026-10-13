package com.bobo.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 接口文档配置（springdoc-openapi）。
 *
 * @author bobo
 */
@Configuration
public class OpenApiConfig {

    private static final String SCHEME = "Bearer";

    @Bean
    public OpenAPI boboOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("bobo 校园二手交易平台 API")
                        .version("1.0.0")
                        .description("""
                                闲鱼风格的校园闲置交易平台接口文档。

                                调试步骤：先调用 POST /api/auth/login 拿到 token，
                                再点右上角 Authorize 填入该 token（不需要带 Bearer 前缀）。
                                """)
                        .contact(new Contact().name("bobo").email("admin@bobo.edu")))
                .components(new Components().addSecuritySchemes(SCHEME,
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .in(SecurityScheme.In.HEADER)
                                .name("Authorization")))
                .addSecurityItem(new SecurityRequirement().addList(SCHEME));
    }
}
