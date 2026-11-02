package app.daybit.global.config

import app.daybit.global.security.LoginUser
import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.security.SecurityRequirement
import io.swagger.v3.oas.models.security.SecurityScheme
import org.springdoc.core.utils.SpringDocUtils
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * OpenAPI 문서: /v3/api-docs (JSON), /swagger-ui (로컬에서만)
 * 프론트는 이 JSON 으로 Orval 클라이언트를 생성해요.
 */
@Configuration
class OpenApiConfig {

    init {
        // @LoginUser 파라미터는 토큰에서 채워지므로 문서에서 숨겨요
        SpringDocUtils.getConfig().addAnnotationsToIgnore(LoginUser::class.java)
    }

    @Bean
    fun openApi(): OpenAPI = OpenAPI()
        .info(
            Info()
                .title("Daybit API")
                .version("v1")
                .description("기준 문서: docs/04_API명세서.md · 규칙 번호는 docs/02_정책서.md"),
        )
        .components(
            Components().addSecuritySchemes(
                BEARER,
                SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT"),
            ),
        )
        .addSecurityItem(SecurityRequirement().addList(BEARER))

    companion object {
        const val BEARER = "bearer"
    }
}
