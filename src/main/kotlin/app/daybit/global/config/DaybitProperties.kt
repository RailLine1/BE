package app.daybit.global.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties(prefix = "daybit")
data class DaybitProperties(
    val publicBaseUrl: String,
    val jwt: Jwt,
    val cors: Cors,
) {
    data class Jwt(
        val secret: String,
        val accessTtl: Duration,
        val refreshTtl: Duration,
    )

    data class Cors(
        // 정확한 주소 또는 와일드카드 패턴 (예: 모든 expo.app 하위 주소)
        val allowedOrigins: List<String>,
    )
}
