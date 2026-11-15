package app.daybit.booth

import app.daybit.TestcontainersConfiguration
import app.daybit.support.MutableClock
import app.daybit.support.MutableClockConfig
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

@Import(TestcontainersConfiguration::class, MutableClockConfig::class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BoothFlowTest(
    @Autowired val mvc: MockMvc,
    @Autowired val json: JsonMapper,
    @Autowired val clock: MutableClock,
) {
    private fun nick() = "b" + UUID.randomUUID().toString().take(8)

    private fun start(nickname: String): String {
        val body = mvc.post("/api/v1/booth/plays") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"nickname":" $nickname "}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.questions.length()") { value(3) }
            jsonPath("$.questions[0].answer") { doesNotExist() }
        }.andReturn().response.contentAsString
        return json.readTree(body).get("playId").asString()
    }

    private fun submit(playId: String, choices: String) = mvc.post("/api/v1/booth/plays/$playId/submit") {
        contentType = MediaType.APPLICATION_JSON
        content = """{"choices":$choices}"""
    }

    @Test
    fun `로그인 없이 닉네임으로 풀고 맞힌 수 · 시간 순으로 랭킹에 올라요`() {
        val fast = nick(); val slow = nick(); val wrong = nick()
        val fastId = start(fast); val slowId = start(slow); val wrongId = start(wrong)

        clock.advance(java.time.Duration.ofSeconds(5))
        submit(fastId, "[1,1,2]").andExpect {
            status { isOk() }
            jsonPath("$.correct") { value(3) }
            jsonPath("$.total") { value(3) }
            jsonPath("$.elapsedMs") { value(5000) }
            jsonPath("$.answers[2].answer") { value(2) }
        }
        clock.advance(java.time.Duration.ofSeconds(5))
        submit(slowId, "[1,1,2]").andExpect { jsonPath("$.elapsedMs") { value(10000) } }
        submit(wrongId, "[0,1,2]").andExpect { jsonPath("$.correct") { value(2) } }

        val rows = json.readTree(mvc.get("/api/v1/booth/ranking?limit=500").andExpect { status { isOk() } }.andReturn().response.contentAsString)
            .get("rows").toList().map { it.get("nickname").asString() }
        assertThat(rows.filter { it in listOf(fast, slow, wrong) }).containsExactly(fast, slow, wrong)
    }

    @Test
    fun `닉네임은 한 번만, 제출도 한 번만`() {
        val n = nick()
        val id = start(n)
        mvc.post("/api/v1/booth/plays") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"nickname":"$n"}"""
        }.andExpect { status { isConflict() }; jsonPath("$.error.code") { value("NICKNAME_TAKEN") } }

        submit(id, "[1,1]").andExpect { status { isBadRequest() } }
        submit(id, "[1,1,2]").andExpect { status { isOk() } }
        submit(id, "[1,1,2]").andExpect { status { isConflict() }; jsonPath("$.error.code") { value("ALREADY_ATTEMPTED") } }
        submit(UUID.randomUUID().toString(), "[1,1,2]").andExpect { status { isNotFound() } }
        mvc.post("/api/v1/booth/plays") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"nickname":"   "}"""
        }.andExpect { status { isBadRequest() } }
    }
}
