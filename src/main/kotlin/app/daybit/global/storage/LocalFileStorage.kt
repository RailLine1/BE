package app.daybit.global.storage

import app.daybit.global.error.ApiException
import app.daybit.global.error.ErrorCode
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 로컬 개발 · 테스트용 디스크 저장소.
 * 임시 URL 은 서버의 `/local-files/{key}?exp=&sig=` 이고, 서명(HMAC)과 만료 시각으로 검증해요(LocalFileController).
 */
class LocalFileStorage(
    private val root: Path,
    private val publicBaseUrl: String,
    secret: String,
) : FileStorage {

    private val keySpec = SecretKeySpec(secret.toByteArray(), "HmacSHA256")

    override fun put(key: String, bytes: ByteArray, contentType: String) {
        val path = resolve(key)
        Files.createDirectories(path.parent)
        Files.write(path, bytes)
    }

    override fun get(key: String): ByteArray = Files.readAllBytes(resolve(key))

    override fun delete(key: String) {
        Files.deleteIfExists(resolve(key))
    }

    override fun temporaryUrl(key: String, ttl: Duration): TemporaryUrl {
        val exp = Instant.now().plus(ttl).epochSecond
        val encodedKey = key.split('/').joinToString("/") { URLEncoder.encode(it, StandardCharsets.UTF_8) }
        val url = "${publicBaseUrl.trimEnd('/')}/local-files/$encodedKey?exp=$exp&sig=${sign(key, exp)}"
        return TemporaryUrl(url, OffsetDateTime.ofInstant(Instant.ofEpochSecond(exp), ZoneId.of("Asia/Seoul")))
    }

    /** 서명과 만료를 확인하고 파일을 돌려줘요. 틀리거나 만료되면 404 (주소가 있는지 드러내지 않아요) */
    fun readSigned(key: String, exp: Long, sig: String): ByteArray {
        val valid = MessageDigest.isEqual(sign(key, exp).toByteArray(), sig.toByteArray())
        if (!valid || Instant.now().epochSecond > exp) throw ApiException(ErrorCode.NOT_FOUND)
        val path = resolve(key)
        if (!Files.exists(path)) throw ApiException(ErrorCode.NOT_FOUND)
        return Files.readAllBytes(path)
    }

    private fun sign(key: String, exp: Long): String {
        val mac = Mac.getInstance("HmacSHA256").apply { init(keySpec) }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal("$key:$exp".toByteArray()))
    }

    /** 키가 저장소 폴더 밖을 가리키지 못하게 막아요 */
    private fun resolve(key: String): Path {
        val path = root.resolve(key).normalize()
        if (!path.startsWith(root)) throw ApiException(ErrorCode.NOT_FOUND)
        return path
    }
}
