package app.daybit.push

import app.daybit.TestcontainersConfiguration
import app.daybit.notification.NotificationType
import app.daybit.support.Fixtures
import app.daybit.support.TestUser
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.post
import tools.jackson.databind.json.JsonMapper
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/** FCM 대신 받은 메시지를 모아 두는 가짜 발송기. invalid 에 넣은 토큰은 무효로 응답해요 */
class FakePushSender : PushSender {
    val sent = CopyOnWriteArrayList<Pair<String, PushMessage>>()
    val invalid = mutableSetOf<String>()
    override fun send(token: String, message: PushMessage): Boolean {
        sent += token to message
        return token !in invalid
    }
}

@TestConfiguration(proxyBeanMethods = false)
class FakePushConfig {
    @Bean
    @Primary
    fun fakePushSender() = FakePushSender()
}

@Import(TestcontainersConfiguration::class, FakePushConfig::class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PushFlowTest(
    @Autowired val mvc: MockMvc,
    @Autowired val json: JsonMapper,
    @Autowired val jdbc: JdbcClient,
    @Autowired val fake: FakePushSender,
    @Autowired val push: PushService,
) {
    private val f = Fixtures(mvc, json, jdbc)

    @BeforeEach
    fun reset() {
        fake.sent.clear()
        fake.invalid.clear()
    }

    private fun token() = "fcm-" + UUID.randomUUID()

    private fun register(user: TestUser, token: String, platform: String = "android") =
        mvc.post("/api/v1/push/tokens") {
            header("Authorization", user.bearer)
            contentType = MediaType.APPLICATION_JSON
            content = """{"token":"$token","platform":"$platform"}"""
        }

    private fun owner(token: String): Long? =
        jdbc.sql("select user_id from push_tokens where token = :t").param("t", token).query(Long::class.java).optional().orElse(null)

    @Test
    fun `같은 토큰은 갱신하고, 다른 계정에서 등록하면 그 계정으로 옮겨요`() {
        val a = f.newUser()
        val b = f.newUser()
        val t = token()
        register(a, t).andExpect { status { isNoContent() } }
        register(a, t, "web").andExpect { status { isNoContent() } }
        assertThat(owner(t)).isEqualTo(a.id)
        assertThat(jdbc.sql("select platform from push_tokens where token = :t").param("t", t).query(String::class.java).single())
            .isEqualTo("web")

        register(b, t).andExpect { status { isNoContent() } }
        assertThat(owner(t)).isEqualTo(b.id)
        register(a, "", "android").andExpect { status { isBadRequest() } }
        register(a, token(), "windows").andExpect { status { isBadRequest() } }
    }

    @Test
    fun `토큰 삭제는 내 토큰만 지우고, 없어도 204 예요`() {
        val a = f.newUser()
        val b = f.newUser()
        val t = token()
        register(a, t)
        mvc.delete("/api/v1/push/tokens") {
            header("Authorization", b.bearer); contentType = MediaType.APPLICATION_JSON; content = """{"token":"$t"}"""
        }.andExpect { status { isNoContent() } }
        assertThat(owner(t)).isEqualTo(a.id)
        mvc.delete("/api/v1/push/tokens") {
            header("Authorization", a.bearer); contentType = MediaType.APPLICATION_JSON; content = """{"token":"$t"}"""
        }.andExpect { status { isNoContent() } }
        assertThat(owner(t)).isNull()
    }

    @Test
    fun `가입이 승인되면 요청자의 모든 기기로 푸시가 가요`() {
        val admin = f.newUser()
        val requester = f.newUser()
        val phone = token()
        val web = token()
        register(requester, phone)
        register(requester, web, "web")
        val (courseId, _) = f.createCourse(admin, visibility = "private")
        f.join(courseId, requester, status = "pending")

        mvc.post("/api/v1/courses/$courseId/join-requests/${requester.id}/approve") { header("Authorization", admin.bearer) }
            .andExpect { status { isNoContent() } }

        val messages = waitSent(2)
        assertThat(messages.map { it.first }).containsExactlyInAnyOrder(phone, web)
        val message = messages.first().second
        assertThat(message.title).isEqualTo("운영체제")
        assertThat(message.body).isEqualTo("가입 요청이 승인됐어요.")
        assertThat(message.data).containsEntry("type", "join_approved").containsEntry("courseId", courseId.toString())
    }

    @Test
    fun `FCM 이 무효라고 답한 토큰은 지워요`() {
        val user = f.newUser()
        val good = token()
        val bad = token()
        register(user, good)
        register(user, bad)
        fake.invalid += bad

        push.dispatch(listOf(user.id), NotificationType.bit_open, mapOf("courseId" to 1, "courseName" to "운영체제", "bitId" to 9, "no" to 3))

        assertThat(owner(bad)).isNull()
        assertThat(owner(good)).isEqualTo(user.id)
        val message = fake.sent.first { it.first == good }.second
        assertThat(message.title).isEqualTo("운영체제 · 오늘의 한 조각")
        assertThat(message.body).isEqualTo("3번째 문제가 열렸어요.")
        assertThat(message.data).containsEntry("bitId", "9")
    }

    /** 발송은 커밋 뒤 별도 스레드에서 돌아요 */
    private fun waitSent(count: Int): List<Pair<String, PushMessage>> {
        repeat(50) {
            if (fake.sent.size >= count) return fake.sent.toList()
            Thread.sleep(100)
        }
        error("푸시가 ${count}건 오지 않았어요: ${fake.sent.size}")
    }
}
