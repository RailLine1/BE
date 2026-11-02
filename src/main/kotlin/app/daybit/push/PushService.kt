package app.daybit.push

import app.daybit.notification.NotificationType
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager

@Service
class PushService(
    private val jdbc: JdbcClient,
    private val sender: PushSender,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 같은 토큰이 다시 오면 지금 사용자로 옮기고 갱신해요 (기기에서 다른 계정으로 로그인한 경우) */
    @Transactional
    fun register(userId: Long, token: String, platform: PushPlatform) {
        jdbc.sql(
            """
            insert into push_tokens (user_id, token, platform) values (:u, :t, :p)
            on conflict (token) do update set user_id = :u, platform = :p, last_seen_at = now()
            """.trimIndent(),
        ).param("u", userId).param("t", token.trim()).param("p", platform.name).update()
    }

    /** 내 토큰만 지워요. 없어도 204 */
    @Transactional
    fun unregister(userId: Long, token: String) {
        jdbc.sql("delete from push_tokens where user_id = :u and token = :t").param("u", userId).param("t", token.trim()).update()
    }

    /**
     * 알림을 기록한 트랜잭션이 커밋되면 별도 가상 스레드에서 보내요 (P-NOTI-03).
     * 롤백되면 보내지 않아요. 발송 실패는 알림 기록에 영향을 주지 않아요.
     */
    fun dispatchAfterCommit(userIds: Collection<Long>, type: NotificationType, payload: Map<String, Any?>) {
        if (userIds.isEmpty()) return
        val users = userIds.toList()
        val task = Runnable { Thread.ofVirtual().start { dispatch(users, type, payload) } }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                override fun afterCommit() = task.run()
            })
        } else {
            task.run()
        }
    }

    fun dispatch(userIds: List<Long>, type: NotificationType, payload: Map<String, Any?>) {
        val message = messageOf(type, payload)
        val tokens = jdbc.sql("select token from push_tokens where user_id in (:ids)").param("ids", userIds)
            .query(String::class.java).list().filterNotNull()
        tokens.forEach { token ->
            val ok = runCatching { sender.send(token, message) }
                .onFailure { log.warn("푸시 발송 오류", it) }
                .getOrDefault(true)
            if (!ok) jdbc.sql("delete from push_tokens where token = :t").param("t", token).update()
        }
    }

    /** 알림 문구와 앱이 화면 이동에 쓰는 data (값은 모두 문자열) */
    internal fun messageOf(type: NotificationType, payload: Map<String, Any?>): PushMessage {
        val course = payload["courseName"]?.toString() ?: "Daybit"
        val (title, body) = when (type) {
            NotificationType.bit_open -> "$course · 오늘의 한 조각" to "${payload["no"] ?: ""}번째 문제가 열렸어요."
            NotificationType.join_approved -> course to "가입 요청이 승인됐어요."
            NotificationType.promoted_admin -> course to "이 과목의 관리자가 되었어요."
            NotificationType.quiz_source_fallback -> course to "퀴즈 출처가 Gemini로 바뀌었어요."
        }
        val data = buildMap {
            put("type", type.name)
            payload["courseId"]?.let { put("courseId", it.toString()) }
            payload["bitId"]?.let { put("bitId", it.toString()) }
        }
        return PushMessage(title, body, data)
    }
}
