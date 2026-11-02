package app.daybit.notification

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper

/** P-NOTI-03 알림 종류 (notifications.type) */
@Suppress("EnumEntryName")
enum class NotificationType { bit_open, join_approved, promoted_admin, quiz_source_fallback }

/**
 * 알림을 notifications 테이블에 쌓아요.
 * 실제 푸시(FCM) 발송은 push 모듈이 생기면 여기서 이어 붙여요.
 */
@Component
class Notifier(private val jdbc: JdbcClient, private val json: JsonMapper) {

    fun notify(userIds: Collection<Long>, type: NotificationType, payload: Map<String, Any?>) {
        if (userIds.isEmpty()) return
        val body = json.writeValueAsString(payload)
        userIds.forEach { userId ->
            jdbc.sql("insert into notifications (user_id, type, payload) values (:userId, :type, cast(:payload as jsonb))")
                .param("userId", userId)
                .param("type", type.name)
                .param("payload", body)
                .update()
        }
    }
}
