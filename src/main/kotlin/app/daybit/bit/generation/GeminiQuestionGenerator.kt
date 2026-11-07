package app.daybit.bit.generation

import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

/**
 * Gemini 로 문제를 만들어요 (P-BIT-14). 호출 · 모델 대체는 GeminiClient 가 해요.
 */
@Component
class GeminiQuestionGenerator(
    private val gemini: GeminiClient,
    private val json: JsonMapper,
    private val prompt: QuestionPrompt,
) : QuestionGenerator {

    override val enabled: Boolean get() = gemini.enabled

    override fun generate(request: GenerationRequest): List<GeneratedQuestion> {
        val text = gemini.generateJson(prompt.system, prompt.user(request), prompt.geminiSchema(), temperature = 0.4)
        return json.readTree(text).path("questions").toList().map { it.toQuestion() }
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
}
