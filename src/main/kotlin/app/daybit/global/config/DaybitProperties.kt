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
    val github: Github = Github(),
    val workspace: Workspace = Workspace(),
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
        /** 앞에서부터 시도해요. 없어졌거나(404) 혼잡하면(429 · 5xx) 다음 모델로 넘어가요 */
        val models: List<String> = listOf("gemini-3.8-flash", "gemini-flash-latest", "gemini-3.5-flash", "gemini-flash-lite-latest"),
        /** 테스트에서 가짜 서버로 바꿀 때만 써요 */
        val baseUrl: String = "https://generativelanguage.googleapis.com/v1beta",
        /** 모델 하나의 응답을 기다리는 시간. 생각(thinking)이 긴 모델은 3분 넘게 걸리기도 해요 */
        val readTimeout: java.time.Duration = java.time.Duration.ofMinutes(5),
    )

    data class S3(
        val endpoint: String = "",
        val bucket: String = "",
        val accessKey: String = "",
        val secretKey: String = "",
    )

    /**
     * GitHub OAuth 앱 (결정 D3). allowedRedirects 는 로그인 뒤 돌아갈 앱 주소 패턴이에요(`*` 사용 가능).
     * 목록에 없는 주소로는 일회용 code 를 보내지 않아요.
     */
    data class Github(
        val clientId: String = "",
        val clientSecret: String = "",
        val allowedRedirects: List<String> = listOf("daybit://*", "exp://*", "http://localhost:*"),
    )

    /**
     * AI 작업 공간 (#10, 결정 D11). 관리자 GitHub 에 templateRepo 를 복사해 repoName 저장소를 만들고,
     * workflow 를 실행해 문제를 받아요. 워크플로의 OIDC 토큰 audience 는 oidcAudience 여야 해요.
     */
    data class Workspace(
        val templateRepo: String = "RailLine1/AI",
        val repoName: String = "daybit-workspace",
        val workflow: String = "daybit-generate.yml",
        val oidcAudience: String = "daybit",
        val jobTimeout: java.time.Duration = java.time.Duration.ofMinutes(30),
    )

    /** FCM 발송용 서비스 계정 JSON(원문 또는 base64). 비어 있으면 푸시를 보내지 않아요 */
    data class Firebase(val credentials: String = "")
}
