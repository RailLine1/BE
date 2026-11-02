package app.daybit.push

import app.daybit.global.config.DaybitProperties
import com.google.auth.oauth2.GoogleCredentials
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.RestClient
import tools.jackson.databind.json.JsonMapper
import java.io.ByteArrayInputStream
import java.util.Base64

data class PushMessage(val title: String, val body: String, val data: Map<String, String>)

/** 기기 하나에 보내요. 토큰이 무효면 false (호출한 쪽이 토큰을 지워요) */
fun interface PushSender {
    fun send(token: String, message: PushMessage): Boolean
}

/**
 * FCM HTTP v1 으로 보내요 (결정 D4). 서비스 계정 JSON 이 없으면 보내지 않고 로그만 남겨요.
 * 무효 토큰 판단: 404 UNREGISTERED, 400 INVALID_ARGUMENT (토큰 형식 오류)
 */
@Component
class FcmPushSender(
    props: DaybitProperties,
    private val json: JsonMapper,
) : PushSender {
    private val log = LoggerFactory.getLogger(javaClass)
    private val client = RestClient.create()

    private val credentials: GoogleCredentials?
    private val projectId: String?

    init {
        val raw = props.firebase.credentials.trim()
        if (raw.isEmpty()) {
            credentials = null
            projectId = null
        } else {
            // 원문 JSON 또는 base64 로 인코딩한 JSON 을 받아요 (Railway 변수에 줄바꿈을 넣기 어려워서)
            val text = if (raw.startsWith("{")) raw else String(Base64.getDecoder().decode(raw))
            credentials = GoogleCredentials.fromStream(ByteArrayInputStream(text.toByteArray()))
                .createScoped("https://www.googleapis.com/auth/firebase.messaging")
            projectId = json.readTree(text).path("project_id").asString()
        }
    }

    override fun send(token: String, message: PushMessage): Boolean {
        val creds = credentials ?: run {
            log.debug("Firebase 설정이 없어 푸시를 건너뛰어요: {}", message.title)
            return true
        }
        creds.refreshIfExpired()
        val body = mapOf(
            "message" to mapOf(
                "token" to token,
                "notification" to mapOf("title" to message.title, "body" to message.body),
                "data" to message.data,
            ),
        )
        return try {
            client.post()
                .uri("https://fcm.googleapis.com/v1/projects/{project}/messages:send", projectId)
                .header("Authorization", "Bearer ${creds.accessToken!!.tokenValue}")
                .contentType(MediaType.APPLICATION_JSON)
                .body(json.writeValueAsString(body))
                .retrieve()
                .toBodilessEntity()
            true
        } catch (e: HttpClientErrorException) {
            val invalid = e.statusCode.value() == 404 ||
                (e.statusCode.value() == 400 && e.responseBodyAsString.contains("INVALID_ARGUMENT"))
            if (!invalid) log.warn("푸시 실패: {}", e.responseBodyAsString)
            !invalid
        }
    }
}
