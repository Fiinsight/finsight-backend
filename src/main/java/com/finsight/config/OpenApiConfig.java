package com.finsight.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Swagger UI / OpenAPI metadata (served by springdoc-openapi at
 * /swagger-ui.html and /v3/api-docs — no extra code needed beyond this,
 * springdoc reads it straight off the existing @RestController / record DTOs).
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI finsightOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("FinSight Backend API")
                        .description("경제 뉴스를 이해하고 스스로 투자 판단을 내리도록 돕는 FinSight의 Spring Boot 백엔드 API 문서입니다. "
                                + "모바일 앱(finsight-frontend)이 호출하는 /api/** 엔드포인트를 확인할 수 있습니다.")
                        .version("v0.1.0")
                        .contact(new Contact().name("FinSight")));
    }
}
