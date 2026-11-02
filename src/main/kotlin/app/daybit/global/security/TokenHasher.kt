package app.daybit.global.security

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** 리프레시 토큰 · 커넥터 토큰 같은 "원문은 저장하지 않는" 비밀값 처리 */
object TokenHasher {
    private val random = SecureRandom()

    /** URL 에 그대로 넣을 수 있는 무작위 토큰 (기본 32바이트) */
    fun newToken(bytes: Int = 32): String {
        val buf = ByteArray(bytes).also(random::nextBytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf)
    }

    fun sha256(raw: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
}
