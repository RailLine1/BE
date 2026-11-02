package app.daybit.workspace

import app.daybit.global.config.DaybitProperties
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator
import org.springframework.security.oauth2.core.OAuth2Error
import org.springframework.security.oauth2.core.OAuth2TokenValidator
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtValidators
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 관리자 GitHub 토큰 암호화 (AES-GCM). 키는 JWT 비밀값에서 만들어요(새 환경 변수 없음).
 * JWT 비밀값을 바꾸면 저장된 토큰을 못 읽고, 관리자가 1단계를 다시 하면 돼요.
 */
@Component
class TokenCipher(props: DaybitProperties) {
    private val key = SecretKeySpec(
        MessageDigest.getInstance("SHA-256").digest("daybit-workspace:${props.jwt.secret}".toByteArray()),
        "AES",
    )
    private val random = SecureRandom()

    fun encrypt(plain: String): String {
        val iv = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv)) }
        return Base64.getEncoder().encodeToString(iv + cipher.doFinal(plain.toByteArray()))
    }

    fun decrypt(encoded: String): String {
        val bytes = Base64.getDecoder().decode(encoded)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            .apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(0, 12))) }
        return String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)))
    }
}

/**
 * 워크플로가 보낸 요청이 정말 그 저장소의 GitHub Actions 에서 왔는지 확인해요 (결정 D11).
 * GitHub 가 서명한 OIDC 토큰의 repository 클레임을 돌려줘요. 틀리면 예외.
 */
fun interface ActionsTokenVerifier {
    fun repository(token: String): String
}

@Component
class GithubOidcVerifier(props: DaybitProperties) : ActionsTokenVerifier {
    private val decoder = NimbusJwtDecoder.withJwkSetUri("$ISSUER/.well-known/jwks").build().apply {
        val audience = OAuth2TokenValidator<Jwt> { jwt ->
            if (jwt.audience?.contains(props.workspace.oidcAudience) == true) OAuth2TokenValidatorResult.success()
            else OAuth2TokenValidatorResult.failure(OAuth2Error("invalid_token", "audience 가 맞지 않아요", null))
        }
        setJwtValidator(DelegatingOAuth2TokenValidator(JwtValidators.createDefaultWithIssuer(ISSUER), audience))
    }

    override fun repository(token: String): String =
        decoder.decode(token).getClaimAsString("repository") ?: error("repository 클레임이 없어요")

    companion object {
        const val ISSUER = "https://token.actions.githubusercontent.com"
    }
}
