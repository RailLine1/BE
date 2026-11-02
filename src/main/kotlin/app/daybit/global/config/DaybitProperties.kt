package app.daybit.global.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties(prefix = "daybit")
data class DaybitProperties(
    val publicBaseUrl: String,
    val jwt: Jwt,
    val cors: Cors,
    val storage: Storage = Storage(),
    val gemini: Gemini = Gemini(),
    val firebase: Firebase = Firebase(),
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

    @Suppress("EnumEntryName")
    enum class StorageType { local, s3 }

    /** 학습자료 원본 저장소. 운영은 s3(Cloudflare R2), 로컬 · 테스트는 local(디스크) */
    data class Storage(
        val type: StorageType = StorageType.local,
        val localDir: String = "./data/storage",
        val s3: S3 = S3(),
    )

    /** 기본 퀴즈 출처 (P-BIT-14). 키가 비어 있으면 문제를 만들지 않아요 */
    data class Gemini(
        val apiKey: String = "",
        val model: String = "gemini-2.5-flash",
    )

    data class S3(
        val endpoint: String = "",
        val bucket: String = "",
        val accessKey: String = "",
        val secretKey: String = "",
    )

    /** FCM 발송용 서비스 계정 JSON(원문 또는 base64). 비어 있으면 푸시를 보내지 않아요 */
    data class Firebase(val credentials: String = "")
}
