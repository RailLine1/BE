package app.daybit.global.security

import app.daybit.global.config.DaybitProperties
import app.daybit.global.error.ErrorCode
import app.daybit.global.error.ErrorResponse
import jakarta.servlet.http.HttpServletResponse
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.invoke
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.crypto.factory.PasswordEncoderFactories
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.access.AccessDeniedHandler
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.CorsConfigurationSource
import org.springframework.web.cors.UrlBasedCorsConfigurationSource
import tools.jackson.databind.json.JsonMapper

@Configuration
class SecurityConfig(
    private val props: DaybitProperties,
    private val jsonMapper: JsonMapper,
) {

    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http {
            csrf { disable() }                 // 쿠키 세션을 쓰지 않는 토큰 기반 API
            cors { }
            httpBasic { disable() }
            formLogin { disable() }
            sessionManagement { sessionCreationPolicy = SessionCreationPolicy.STATELESS }
            authorizeHttpRequests {
                authorize(HttpMethod.OPTIONS, "/**", permitAll)
                authorize("/api/v1/auth/**", permitAll)          // 🔓
                authorize("/actuator/health/**", permitAll)
                authorize("/actuator/info", permitAll)            // 배포된 커밋 확인
                authorize("/v3/api-docs/**", permitAll)
                authorize("/swagger-ui/**", permitAll)
                authorize("/swagger-ui.html", permitAll)
                authorize("/mcp/**", permitAll)                  // 커넥터: 주소 안의 토큰으로 자체 인증 (P-AI-02)
                authorize("/local-files/**", permitAll)          // 로컬 저장소 임시 URL: 서명으로 자체 검증
                authorize(anyRequest, authenticated)
            }
            oauth2ResourceServer {
                jwt { }
                authenticationEntryPoint = entryPoint()
                accessDeniedHandler = deniedHandler()
            }
            exceptionHandling {
                authenticationEntryPoint = entryPoint()
                accessDeniedHandler = deniedHandler()
            }
        }
        return http.build()
    }

    @Bean
    fun passwordEncoder(): PasswordEncoder = PasswordEncoderFactories.createDelegatingPasswordEncoder()

    @Bean
    fun corsConfigurationSource(): CorsConfigurationSource {
        val config = CorsConfiguration().apply {
            // 패턴 허용: EAS Hosting 미리보기 주소(https://<프로젝트>--<id>.expo.app)는 배포마다 바뀌어요
            allowedOriginPatterns = props.cors.allowedOrigins
            allowedMethods = listOf("GET", "POST", "PATCH", "PUT", "DELETE", "OPTIONS")
            allowedHeaders = listOf("*")
            maxAge = 3600
        }
        return UrlBasedCorsConfigurationSource().apply { registerCorsConfiguration("/**", config) }
    }

    private fun entryPoint() = AuthenticationEntryPoint { _, response, _ ->
        write(response, ErrorCode.UNAUTHORIZED)
    }

    private fun deniedHandler() = AccessDeniedHandler { _, response, _ ->
        write(response, ErrorCode.FORBIDDEN)
    }

    private fun write(response: HttpServletResponse, code: ErrorCode) {
        response.status = code.status.value()
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.characterEncoding = "UTF-8"
        jsonMapper.writeValue(response.outputStream, ErrorResponse.of(code))
    }
}
