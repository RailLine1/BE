package app.daybit.auth

import app.daybit.global.config.DaybitProperties
import app.daybit.global.error.ApiException
import app.daybit.global.error.ErrorCode
import app.daybit.global.security.TokenHasher
import org.springframework.security.oauth2.jose.jws.MacAlgorithm
import org.springframework.security.oauth2.jwt.JwsHeader
import org.springframework.security.oauth2.jwt.JwtClaimsSet
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.security.oauth2.jwt.JwtEncoderParameters
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.OffsetDateTime

/**
 * 액세스 토큰(JWT, 짧게) + 리프레시 토큰(무작위 문자열, 길게).
 * 리프레시는 쓸 때마다 새로 발급하고 이전 것은 폐기해요(회전).
 */
@Service
class TokenService(
    private val jwtEncoder: JwtEncoder,
    private val refreshTokens: RefreshTokenRepository,
    private val props: DaybitProperties,
) {

    @Transactional
    fun issue(userId: Long): TokenPair {
        val now = Instant.now()
        val claims = JwtClaimsSet.builder()
            .issuer("daybit")
            .subject(userId.toString())
            .issuedAt(now)
            .expiresAt(now.plus(props.jwt.accessTtl))
            .build()
        val header = JwsHeader.with(MacAlgorithm.HS256).build()
        val access = jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).tokenValue

        val rawRefresh = TokenHasher.newToken()
        refreshTokens.save(
            RefreshToken(
                userId = userId,
                tokenHash = TokenHasher.sha256(rawRefresh),
                expiresAt = OffsetDateTime.now().plus(props.jwt.refreshTtl),
            ),
        )
        return TokenPair(access, rawRefresh, props.jwt.accessTtl.seconds)
    }

    /** 리프레시 토큰으로 새 토큰 쌍 발급. 반환값은 (userId, 새 토큰) */
    @Transactional
    fun rotate(rawRefresh: String): Pair<Long, TokenPair> {
        val token = refreshTokens.findByTokenHash(TokenHasher.sha256(rawRefresh))
            ?: throw ApiException(ErrorCode.INVALID_REFRESH_TOKEN)
        val now = OffsetDateTime.now()
        if (!token.isUsable(now)) throw ApiException(ErrorCode.INVALID_REFRESH_TOKEN)
        token.revokedAt = now
        return token.userId to issue(token.userId)
    }

    @Transactional
    fun revoke(rawRefresh: String) {
        refreshTokens.findByTokenHash(TokenHasher.sha256(rawRefresh))?.let {
            if (it.revokedAt == null) it.revokedAt = OffsetDateTime.now()
        }
    }
}

data class TokenPair(
    val accessToken: String,
    val refreshToken: String,
    /** 액세스 토큰 유효 시간(초) */
    val expiresIn: Long,
)
