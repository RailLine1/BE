package app.daybit.bit.generation

import app.daybit.global.config.DaybitProperties
import org.springframework.http.MediaType
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClientResponseException
import org.springframework.web.client.RestClient
import org.springframework.web.client.body
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Duration

/**
 * Gemini generateContent REST API 로 문제를 만들어요 (P-BIT-14). JSON 스키마를 지정해 응답 형식을 고정해요.
 * Spring AI 대신 REST 를 직접 부르는 이유는 docs/decisions.md D8 에 있어요.
 */
@Component
class GeminiQuestionGenerator(
    private val props: DaybitProperties,
    private val json: JsonMapper,
) : QuestionGenerator {
    private val log = LoggerFactory.getLogger(javaClass)

    private val client = RestClient.builder()
        .baseUrl(props.gemini.baseUrl)
        .requestFactory(SimpleClientHttpRequestFactory().apply {
            setConnectTimeout(Duration.ofSeconds(10))
            setReadTimeout(Duration.ofSeconds(120))
        })
        .build()

    override val enabled: Boolean get() = props.gemini.apiKey.isNotBlank()

    override fun generate(request: GenerationRequest): List<GeneratedQuestion> {
        val body = mapOf(
            "systemInstruction" to mapOf("parts" to listOf(mapOf("text" to SYSTEM_PROMPT))),
            "contents" to listOf(mapOf("role" to "user", "parts" to listOf(mapOf("text" to userPrompt(request))))),
            "generationConfig" to mapOf(
                "temperature" to 0.4,
                "responseMimeType" to "application/json",
                "responseSchema" to RESPONSE_SCHEMA,
            ),
        )
        val response = callWithFallback(json.writeValueAsString(body))
        val text = json.readTree(response).path("candidates").path(0).path("content").path("parts").path(0).path("text").asString()
        if (text.isNullOrBlank()) error("Gemini 응답에 내용이 없어요")
        return json.readTree(text).path("questions").toList().map { it.toQuestion() }
    }

    /**
     * 모델 목록을 앞에서부터 시도해요. 모델이 없어졌거나(404) 사용량 · 혼잡(429, 5xx) · 시간 초과면 다음 모델로 넘어가요.
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
            }
        }
        throw last!!
    }

    private fun JsonNode.toQuestion() = GeneratedQuestion(
        concept = path("concept").asString(""),
        stem = path("stem").asString(""),
        choices = path("choices").toList().map { it.asString("") },
        answerIdx = path("answerIndex").asInt(-1),
        explanation = path("explanation").asString(""),
        evidencePage = path("evidencePage").asInt(-1),
        evidenceQuote = path("evidenceQuote").asString(""),
    )

    private fun userPrompt(r: GenerationRequest): String = buildString {
        appendLine("과목: ${r.courseName}")
        appendLine("자료: ${r.fileTitle}")
        appendLine("만들 문제 수: ${r.count}")
        appendLine()
        r.pages.forEach { p ->
            appendLine("<page number=\"${p.page}\">")
            appendLine(p.text)
            appendLine("</page>")
        }
    }

    companion object {
        private val SYSTEM_PROMPT = """
            너는 대학 강의 자료로 복습용 4지선다 문제를 만드는 출제자다.
            규칙:
            - 자료에 실제로 적힌 내용만으로 문제를 만든다. 자료 밖의 지식이 있어야 풀리는 문제는 만들지 않는다.
            - 서로 다른 핵심 개념을 고르게 다룬다. 같은 개념의 문제는 최대 2개다.
            - 보기는 정확히 4개이고, 정답은 하나다. 오답도 그럴듯하게 만든다. "모두 정답" 같은 보기는 쓰지 않는다.
            - answerIndex 는 정답 보기의 번호(0~3)다.
            - evidencePage 는 정답의 근거가 있는 page number 다.
            - evidenceQuote 는 그 페이지 본문에서 정답의 근거가 되는 문장을 한 글자도 바꾸지 않고 그대로 복사한 것이다(10~200자).
            - explanation 은 정답인 이유를 한두 문장으로 쓴다.
            - concept 는 문제가 다루는 개념 이름(짧은 명사구)이다.
            - 자료와 같은 언어로 쓴다.
        """.trimIndent()

        private val RESPONSE_SCHEMA = mapOf(
            "type" to "OBJECT",
            "properties" to mapOf(
                "questions" to mapOf(
                    "type" to "ARRAY",
                    "items" to mapOf(
                        "type" to "OBJECT",
                        "properties" to mapOf(
                            "concept" to mapOf("type" to "STRING"),
                            "stem" to mapOf("type" to "STRING"),
                            "choices" to mapOf("type" to "ARRAY", "items" to mapOf("type" to "STRING")),
                            "answerIndex" to mapOf("type" to "INTEGER"),
                            "explanation" to mapOf("type" to "STRING"),
                            "evidencePage" to mapOf("type" to "INTEGER"),
                            "evidenceQuote" to mapOf("type" to "STRING"),
                        ),
                        "required" to listOf("concept", "stem", "choices", "answerIndex", "explanation", "evidencePage", "evidenceQuote"),
                    ),
                ),
            ),
            "required" to listOf("questions"),
        )
    }
}
