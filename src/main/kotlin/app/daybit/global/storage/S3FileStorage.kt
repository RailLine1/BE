package app.daybit.global.storage

import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZoneId

/** Cloudflare R2 (S3 호환) 저장소. 임시 URL 은 presigned GET URL 이에요. */
class S3FileStorage(
    private val client: S3Client,
    private val presigner: S3Presigner,
    private val bucket: String,
) : FileStorage {

    override fun put(key: String, bytes: ByteArray, contentType: String) {
        client.putObject({ it.bucket(bucket).key(key).contentType(contentType) }, RequestBody.fromBytes(bytes))
    }

    override fun get(key: String): ByteArray =
        client.getObjectAsBytes { it.bucket(bucket).key(key) }.asByteArray()

    override fun delete(key: String) {
        client.deleteObject { it.bucket(bucket).key(key) }
    }

    override fun temporaryUrl(key: String, ttl: Duration): TemporaryUrl {
        val presigned = presigner.presignGetObject { req ->
            req.signatureDuration(ttl).getObjectRequest { it.bucket(bucket).key(key) }
        }
        return TemporaryUrl(presigned.url().toString(), OffsetDateTime.ofInstant(presigned.expiration(), ZoneId.of("Asia/Seoul")))
    }
}
