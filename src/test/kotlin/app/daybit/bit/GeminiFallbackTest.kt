package app.daybit.bit

import app.daybit.bit.generation.GeminiQuestionGenerator
import app.daybit.bit.generation.GenerationRequest
import app.daybit.bit.generation.PageText
import app.daybit.bit.generation.QuestionPrompt
import app.daybit.global.config.DaybitProperties
import com.sun.net.httpserver.HttpServer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.web.client.RestClientResponseException
import tools.jackson.databind.json.JsonMapper
import java.net.InetSocketAddress
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList

/** 실제 Gemini 대신 로컬 HTTP 서버로 모델 대체 순서를 확인해요 (Spring 컨텍스트 없음) */
class GeminiFallbackTest {

    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val calls = CopyOnWriteArrayList<String>()
    private val bodies = CopyOnWriteArrayList<String>()

    @AfterEach
    fun stop() = server.stop(0)

    /** 모델 이름 → (상태 코드, 본문) */
    private fun generator(responses: Map<String, Pair<Int, String>>, models: List<String>): GeminiQuestionGenerator {
        server.createContext("/") { ex ->
            val model = ex.requestURI.path.substringAfter("/models/").substringBefore(":")
            calls += model
            bodies += String(ex.requestBody.readAllBytes(), Charsets.UTF_8)
            val (code, body) = responses[model] ?: (500 to "{}")
            val bytes = body.toByteArray()
            ex.sendResponseHeaders(code, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()
        val props = DaybitProperties(
            publicBaseUrl = "http://localhost",
            jwt = DaybitProperties.Jwt("x".repeat(32), Duration.ofMinutes(1), Duration.ofDays(1)),
            cors = DaybitProperties.Cors(emptyList()),
            gemini = DaybitProperties.Gemini(apiKey = "test", models = models, baseUrl = "http://127.0.0.1:${server.address.port}"),
        )
        val json = JsonMapper()
        return GeminiQuestionGenerator(props, json, QuestionPrompt(json))
    }

    private val request = GenerationRequest("운영체제", "lec08", listOf(PageText(1, "deadlock")), 3)

    private val okBody = """
        {"candidates":[{"content":{"parts":[{"text":"{\"questions\":[{\"concept\":\"교착 상태\",\"stem\":\"Q\",\"choices\":[\"a\",\"b\",\"c\",\"d\"],\"answerIndex\":2,\"explanation\":\"E\",\"evidencePage\":1,\"evidenceQuote\":\"deadlock\"}]}"}]}}]}
    """.trimIndent()

    @Test
    fun `없어진 모델(404)과 혼잡한 모델(503)은 건너뛰고 다음 모델로 만들어요`() {
        val gen = generator(
            mapOf(
                "old" to (404 to """{"error":{"code":404}}"""),
                "busy" to (503 to """{"error":{"code":503}}"""),
                "good" to (200 to okBody),
            ),
            listOf("old", "busy", "good", "never"),
        )
        val questions = gen.generate(request)
        assertThat(calls).containsExactly("old", "busy", "good")
        assertThat(questions.single().answerIdx).isEqualTo(2)
        assertThat(questions.single().concept).isEqualTo("교착 상태")
    }

    @Test
    fun `프롬프트 파일의 출제 규칙과 스키마(대문자 type)를 그대로 보내요`() {
        val gen = generator(mapOf("good" to (200 to okBody)), listOf("good"))
        gen.generate(request)
        val sent = JsonMapper().readTree(bodies.single())
        assertThat(sent.path("systemInstruction").path("parts").path(0).path("text").asString())
            .startsWith("너는 대학 강의 자료로 복습용 4지선다 문제를 만드는 출제자다.")
        val schema = sent.path("generationConfig").path("responseSchema")
        assertThat(schema.path("type").asString()).isEqualTo("OBJECT")
        assertThat(schema.path("properties").path("questions").path("items").path("properties").path("answerIndex").path("type").asString())
            .isEqualTo("INTEGER")
        assertThat(sent.path("contents").path(0).path("parts").path(0).path("text").asString())
            .contains("만들 문제 수: 3").contains("<page number=\"1\">")
    }

    @Test
    fun `키 문제(403)는 다른 모델로도 같으니 바로 실패해요`() {
        val gen = generator(mapOf("first" to (403 to """{"error":{"code":403}}""")), listOf("first", "second"))
        assertThatThrownBy { gen.generate(request) }.isInstanceOf(RestClientResponseException::class.java)
        assertThat(calls).containsExactly("first")
    }

    @Test
    fun `모든 모델이 실패하면 마지막 오류를 던져요`() {
        val gen = generator(emptyMap(), listOf("a", "b"))
        assertThatThrownBy { gen.generate(request) }.isInstanceOf(RestClientResponseException::class.java)
        assertThat(calls).containsExactly("a", "b")
    }
}
