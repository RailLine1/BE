package app.daybit.global.storage

import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.OffsetDateTime

/**
 * 학습자료 원본 저장소. 운영은 Cloudflare R2(S3 호환), 로컬 개발 · 테스트는 디스크를 써요.
 * `daybit.storage.type` 으로 고르고, 구현은 StorageConfig 에서 만들어요.
 */
interface FileStorage {
    fun put(key: String, bytes: ByteArray, contentType: String)
    fun get(key: String): ByteArray
    fun delete(key: String)

    /** 로그인 없이 원본을 볼 수 있는 임시 URL */
    fun temporaryUrl(key: String, ttl: Duration): TemporaryUrl
}

data class TemporaryUrl(val url: String, val expiresAt: OffsetDateTime)

/**
 * DB 트랜잭션이 커밋된 뒤에 원본을 지워요. 롤백되면 지우지 않아요.
 * 원본 삭제가 실패해도 DB 는 이미 정리됐으니 로그만 남겨요(남은 파일은 접근할 길이 없어요).
 */
fun FileStorage.deleteAfterCommit(keys: Collection<String>) {
    if (keys.isEmpty()) return
    val run = {
        keys.forEach { key ->
            runCatching { delete(key) }.onFailure { storageLog.warn("원본 삭제 실패: {}", key, it) }
        }
    }
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
        TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
            override fun afterCommit() = run()
        })
    } else {
        run()
    }
}

private val storageLog = LoggerFactory.getLogger(FileStorage::class.java)
