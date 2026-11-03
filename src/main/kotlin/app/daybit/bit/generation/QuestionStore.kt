package app.daybit.bit.generation

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.json.JsonMapper

/** 문제를 만들 자료: 과목 · 자료 이름 · 출제 범위 · 페이지 본문(예산까지) */
data class Material(
    val courseId: Long,
    val courseName: String,
    val fileId: Long,
    val title: String,
    val scope: String?,
    val pages: List<PageText>,
) {
    fun request(count: Int) = GenerationRequest(courseName, title, pages, count)
}

/**
 * 문제 생성의 입력 준비와 결과 저장. Gemini(QuestionBankService)와 AI 작업 공간(#10)이 같이 써요.
 * 저장 전에 형식을 검사하고, 근거 문장이 실제 페이지에 있는 문제만 verified 로 표시해요 (07_핵심기술 3).
 */
@Component
class QuestionStore(
    private val jdbc: JdbcClient,
    private val tx: TransactionTemplate,
    private val json: JsonMapper,
) {

    /** ready 자료의 페이지를 읽어요. 자료가 없거나 텍스트가 없으면 null */
    fun material(fileId: Long): Material? {
        val head = jdbc.sql(
            """
            select f.course_id, c.name as course_name, f.title, d.kind
              from files f join folders d on d.id = f.folder_id join courses c on c.id = f.course_id
             where f.id = :id and f.status = 'ready'
            """.trimIndent(),
        ).param("id", fileId)
            .query { rs, _ -> listOf(rs.getLong("course_id"), rs.getString("course_name"), rs.getString("title"), rs.getString("kind")) }
            .optional().orElse(null) ?: return null
        val pages = jdbc.sql("select page_no, text from file_pages where file_id = :id order by page_no").param("id", fileId)
            .query { rs, _ -> PageText(rs.getInt("page_no"), rs.getString("text") ?: "") }.list()
            .filter { it.text.isNotBlank() }
        if (pages.isEmpty()) return null
        val kind = head[3] as String
        return Material(
            courseId = head[0] as Long,
            courseName = head[1] as String,
            fileId = fileId,
            title = head[2] as String,
            scope = kind.takeIf { it == "mid" || it == "final" },
            pages = trimToBudget(pages),
        )
    }

    /** 형식이 맞는 문제만 개념별로 저장하고, 저장한 수를 돌려줘요. source: gemini · claudecode · codex */
    fun save(material: Material, generated: List<GeneratedQuestion>, source: String): Int {
        val pageText = material.pages.associate { it.page to it.text }
        val valid = generated.filter { isWellFormed(it, pageText.keys) }
        if (valid.isEmpty()) return 0
        tx.executeWithoutResult {
            valid.groupBy { it.concept.trim().take(80) }.forEach { (concept, questions) ->
                val conceptId = jdbc.sql(
                    """
                    insert into concepts (course_id, file_id, name, page_from, page_to, capacity)
                    values (:courseId, :fileId, :name, :from, :to, :capacity)
                    returning id
                    """.trimIndent(),
                ).param("courseId", material.courseId).param("fileId", material.fileId).param("name", concept)
                    .param("from", questions.minOf { it.evidencePage }).param("to", questions.maxOf { it.evidencePage })
                    .param("capacity", questions.size).query(Long::class.java).single()

                questions.forEach { q ->
                    jdbc.sql(
                        """
                        insert into questions (course_id, concept_id, scope, stem, choices, answer_idx, explanation,
                                               evidence_file_id, evidence_page, source, verified)
                        values (:courseId, :conceptId, :scope, :stem, cast(:choices as jsonb), :answer, :explanation,
                                :fileId, :page, :source, :verified)
                        """.trimIndent(),
                    ).param("courseId", material.courseId).param("conceptId", conceptId).param("scope", material.scope)
                        .param("stem", q.stem.trim()).param("choices", json.writeValueAsString(q.choices.map { it.trim() }))
                        .param("answer", q.answerIdx).param("explanation", q.explanation.trim())
                        .param("fileId", material.fileId).param("page", q.evidencePage).param("source", source)
                        .param("verified", isGrounded(q, pageText.getValue(q.evidencePage)))
                        .update()
                }
            }
        }
        return valid.size
    }

    /** 보기 4개(중복 없음) · 정답 번호 0~3 · 있는 페이지를 근거로 든 문제만 */
    fun isWellFormed(q: GeneratedQuestion, pages: Set<Int>): Boolean =
        q.concept.isNotBlank() && q.stem.isNotBlank() && q.explanation.isNotBlank() &&
            q.choices.size == 4 && q.choices.all { it.isNotBlank() } && q.choices.map { it.trim() }.toSet().size == 4 &&
            q.answerIdx in 0..3 && q.evidencePage in pages

    /**
     * 근거 검증: 모델이 옮긴 근거 문장이 근거 페이지 본문에 실제로 있는지 봐요.
     * PDF 추출과 모델 출력의 줄바꿈 · 공백이 달라서 공백을 모두 지우고 비교해요.
     */
    fun isGrounded(q: GeneratedQuestion, pageText: String): Boolean {
        val quote = normalize(q.evidenceQuote)
        return quote.length >= MIN_QUOTE && normalize(pageText).contains(quote)
    }

    private fun normalize(s: String) = s.lowercase().replace(Regex("\\s+"), "")

    /** 입력이 너무 길면 앞에서부터 예산까지만 보내요 */
    private fun trimToBudget(pages: List<PageText>): List<PageText> {
        var total = 0
        return pages.takeWhile { total += it.text.length; total <= MAX_CHARS }
            .ifEmpty { listOf(pages.first().copy(text = pages.first().text.take(MAX_CHARS))) }
    }

    companion object {
        private const val MIN_QUOTE = 10
        private const val MAX_CHARS = 80_000
    }
}
