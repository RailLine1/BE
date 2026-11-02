package app.daybit.bit.generation

/**
 * 자료 페이지로 4지선다 문제를 만드는 AI. 기본 구현은 Gemini(GeminiQuestionGenerator)예요 (P-BIT-14).
 * 테스트에서는 가짜 구현으로 바꿔 끼워요.
 */
interface QuestionGenerator {
    /** 키가 없거나 꺼져 있으면 false. 이때는 문제를 만들지 않고 건너뛰어요 */
    val enabled: Boolean

    /** 실패하면 예외를 던져요. 형식 검증 · 근거 검증은 QuestionBankService 가 해요 */
    fun generate(request: GenerationRequest): List<GeneratedQuestion>
}

data class GenerationRequest(
    val courseName: String,
    val fileTitle: String,
    /** 1쪽부터 순서대로 */
    val pages: List<PageText>,
    val count: Int,
)

data class PageText(val page: Int, val text: String)

data class GeneratedQuestion(
    val concept: String,
    val stem: String,
    val choices: List<String>,
    val answerIdx: Int,
    val explanation: String,
    val evidencePage: Int,
    /** 근거 페이지에서 그대로 옮긴 문장. 근거 검증에 써요 */
    val evidenceQuote: String,
)
