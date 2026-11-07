package app.daybit.bit.generation

/**
 * 프롬프트 대신 서버가 거르는 출제 규칙 (#52). 모델에는 문제 품질만 맡기고, 코드로 볼 수 있는 건 여기서 봐요.
 * 형식 검사(isWellFormed)를 통과한 문제만 넣어요. 걸리면 이유를, 통과하면 null 을 돌려줘요.
 */
fun ruleViolation(q: GeneratedQuestion): String? {
    if (q.stem.trim().length > MAX_STEM) return "문제가 ${MAX_STEM}자 초과"
    if (q.choices.any { it.trim().length > MAX_CHOICE }) return "보기가 ${MAX_CHOICE}자 초과"
    if (q.explanation.trim().length > MAX_EXPLANATION) return "해설이 ${MAX_EXPLANATION}자 초과"
    val lengths = q.choices.map { it.trim().length }
    val longestOther = lengths.filterIndexed { i, _ -> i != q.answerIdx }.max()
    if (lengths[q.answerIdx] >= longestOther * ANSWER_LENGTH_RATIO) return "정답 보기만 눈에 띄게 긺"
    if (NUMBER_REF.containsMatchIn(q.explanation)) return "해설이 보기를 번호로 가리킴"   // 섞은 뒤엔 번호가 틀려요
    return null
}

/** 자료 본문 속 <page> · </page> 를 지워, 자료가 페이지 경계를 흉내 내지 못하게 해요 */
fun StringBuilder.appendPages(pages: List<PageText>) {
    pages.forEach { p ->
        appendLine("<page number=\"${p.page}\">")
        appendLine(p.text.replace(PAGE_TAG, ""))
        appendLine("</page>")
    }
}

// 길이 상한은 화면이 깨지지 않을 정도로 넉넉하게 둬요. eval 60문제 최대: 문제 123 · 보기 90 · 해설 177자
private const val MAX_STEM = 200
private const val MAX_CHOICE = 100
private const val MAX_EXPLANATION = 300
// eval 60문제 중 5개가 1.5배 이상이었고, 모두 길이만 보고 정답을 고를 수 있었어요
private const val ANSWER_LENGTH_RATIO = 1.5
private val NUMBER_REF = Regex("[①②③④]|[1-4]\\s*번\\s*(보기|선택지)|(보기|선택지)\\s*[1-4]|정답은\\s*[1-4]\\s*번")
private val PAGE_TAG = Regex("</?\\s*page\\b[^>]*>", RegexOption.IGNORE_CASE)
