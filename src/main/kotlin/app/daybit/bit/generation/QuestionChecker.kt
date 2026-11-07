package app.daybit.bit.generation

import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper

/**
 * 정답 숨긴 풀이 검수 (#52). 정답 · 해설 없이 문제 · 보기 · 자료만 받아 보기마다 맞는지 판단해요.
 * 테스트에서는 가짜 구현으로 바꿔 끼워요.
 */
interface QuestionChecker {
    /** 키가 없으면 false. 이때는 검수를 건너뛰고 근거 검증만 봐요 */
    val enabled: Boolean

    /** 문제 id → 보기 4개의 판정. 응답에 없는 id 는 빠져요. 호출이 실패하면 예외를 던져요 */
    fun check(pages: List<PageText>, items: List<CheckItem>): Map<Long, List<Verdict>>
}

data class CheckItem(val id: Long, val stem: String, val choices: List<String>)

/** 보기 하나의 판정: yes · no · unsure */
data class Verdict(val index: Int, val matches: String)

/** 맞는 보기가 정확히 하나이고, 모르는 보기가 없고, 그 보기가 저장된 정답일 때만 통과 */
fun passes(verdicts: List<Verdict>?, answerIdx: Int): Boolean {
    if (verdicts == null || verdicts.map { it.index }.sorted() != listOf(0, 1, 2, 3)) return false
    if (verdicts.any { it.matches !in setOf("yes", "no") }) return false
    return verdicts.filter { it.matches == "yes" }.map { it.index } == listOf(answerIdx)
}

@Component
class GeminiQuestionChecker(
    private val gemini: GeminiClient,
    private val json: JsonMapper,
    private val prompt: QuestionPrompt,
) : QuestionChecker {

    override val enabled: Boolean get() = gemini.enabled

    override fun check(pages: List<PageText>, items: List<CheckItem>): Map<Long, List<Verdict>> {
        val user = buildString {
            appendPages(pages)
            appendLine()
            appendLine("검수할 문제:")
            appendLine(json.writeValueAsString(items))
        }
        val text = gemini.generateJson(prompt.checkSystem, user, prompt.geminiCheckSchema(), temperature = 0.0)
        return json.readTree(text).path("results").toList().groupBy { it.path("id").asLong(-1) }
            .filterValues { it.size == 1 }                       // 같은 id 가 두 번 오면 믿지 않아요
            .mapValues { (_, r) ->
                r.single().path("checks").toList().map { Verdict(it.path("index").asInt(-1), it.path("matches").asString("")) }
            }
    }
}
