package app.daybit.bit.generation

import app.daybit.global.config.DaybitProperties
import org.springframework.http.MediaType
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClientException
import org.springframework.web.client.RestClientResponseException
import org.springframework.web.client.RestClient
import org.springframework.web.client.body
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.io.IOException
import java.time.Duration

/**
 * Gemini generateContent REST API 로 문제를 만들어요 (P-BIT-14). JSON 스키마를 지정해 응답 형식을 고정해요.
 * Spring AI 대신 REST 를 직접 부르는 이유는 docs/decisions.md D8 에 있어요.
 */
@Component
class GeminiQuestionGenerator(
    private val props: DaybitProperties,
    private val json: JsonMapper,
    private val prompt: QuestionPrompt,
) : QuestionGenerator {
    private val log = LoggerFactory.getLogger(javaClass)

    private val client = RestClient.builder()
        .baseUrl(props.gemini.baseUrl)
        .requestFactory(SimpleClientHttpRequestFactory().apply {
            setConnectTimeout(Duration.ofSeconds(10))
            setReadTimeout(props.gemini.readTimeout)
        })
        .build()

    override val enabled: Boolean get() = props.gemini.apiKey.isNotBlank()

    override fun generate(request: GenerationRequest): List<GeneratedQuestion> {
        val body = mapOf(
            "systemInstruction" to mapOf("parts" to listOf(mapOf("text" to prompt.system))),
            "contents" to listOf(mapOf("role" to "user", "parts" to listOf(mapOf("text" to prompt.user(request))))),
            "generationConfig" to mapOf(
                "temperature" to 0.4,
                "responseMimeType" to "application/json",
                "responseSchema" to prompt.geminiSchema(),
            ),
        )
        val response = callWithFallback(json.writeValueAsString(body))
        val text = json.readTree(response).path("candidates").path(0).path("content").path("parts").path(0).path("text").asString()
        if (text.isNullOrBlank()) error("Gemini 응답에 내용이 없어요")
        return json.readTree(text).path("questions").toList().map { it.toQuestion() }
    }

    /**
     * 모델 목록을 앞에서부터 시도해요. 모델이 없어졌거나(404) 사용량 · 혼잡(429, 5xx) · 시간 초과(연결 · 응답 본문 읽기)면 다음 모델로 넘어가요.
     * 그 밖의 오류(400 요청 형식, 401 · 403 키 문제)는 다른 모델로도 똑같이 실패하므로 바로 던져요.
     */
    private fun callWithFallback(body: String): String {
        val models = props.gemini.models.map { it.trim() }.filter { it.isNotEmpty() }
        if (models.isEmpty()) error("Gemini 모델이 설정되지 않았어요")
        var last: Exception? = null
        for (model in models) {
            try {
                val response = client.post()
                    .uri("/models/{model}:generateContent", model)
                    .header("x-goog-api-key", props.gemini.apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body<ByteArray>()?.toString(Charsets.UTF_8) ?: error("Gemini 응답이 비었어요")   // charset 이 빠져도 UTF-8
                log.info("문제 생성 모델: {}", model)
                return response
            } catch (e: RestClientResponseException) {
                val code = e.statusCode.value()
                if (code != 404 && code != 429 && code < 500) throw e
                log.warn("Gemini {} 실패({}), 다음 모델로 넘어가요", model, code)
                last = e
            } catch (e: ResourceAccessException) {
                log.warn("Gemini {} 응답 없음, 다음 모델로 넘어가요", model)
                last = e
            } catch (e: RestClientException) {
                // 응답 헤더는 왔는데 본문을 읽다가 시간이 초과되면 ResourceAccessException 이 아니라 이걸로 감싸져 와요
                if (!e.hasIoCause()) throw e
                log.warn("Gemini {} 응답 본문 읽기 실패({}), 다음 모델로 넘어가요", model, e.rootCause()?.javaClass?.simpleName)
                last = e
            }
        }
        throw last!!
    }

    private fun Throwable.rootCause(): Throwable? = generateSequence(cause) { it.cause }.lastOrNull()

    private fun Throwable.hasIoCause(): Boolean = generateSequence(cause) { it.cause }.any { it is IOException }

    private fun JsonNode.toQuestion() = GeneratedQuestion(
        concept = path("concept").asString(""),
        stem = path("stem").asString(""),
        choices = path("choices").toList().map { it.asString("") },
        answerIdx = path("answerIndex").asInt(-1),
        explanation = path("explanation").asString(""),
        evidencePage = path("evidencePage").asInt(-1),
        evidenceQuote = path("evidenceQuote").asString(""),
    )
}
