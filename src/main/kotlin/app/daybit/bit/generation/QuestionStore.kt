package app.daybit.bit.generation

import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.json.JsonMapper
import kotlin.random.Random

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

/** 저장 결과. pending 은 근거 검증을 통과해 정답 숨긴 검수를 기다리는 문제 id 예요 */
data class SaveResult(val saved: Int, val grounded: Int, val pending: List<Long>)

/** 내 AI 출제 제출 뒤 검수를 별도 스레드에서 돌리려고 내는 이벤트 */
data class QuestionsSavedEvent(val fileId: Long, val ids: List<Long>)

/**
 * 문제 생성의 입력 준비와 결과 저장. Gemini(QuestionBankService)와 내 AI 출제(#48)가 같이 써요.
 * 저장 전에 형식 · 출제 규칙(QuestionRules)을 검사하고 보기를 섞어요. 근거 문장이 실제 페이지에 있고(07_핵심기술 3)
 * 정답 숨긴 검수(#52)를 통과한 문제만 verified 로 표시해요.
 */
@Component
class QuestionStore(
    private val jdbc: JdbcClient,
    private val tx: TransactionTemplate,
    private val json: JsonMapper,
    private val checker: QuestionChecker,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    var random: Random = Random.Default

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

    /**
     * 형식이 맞는 문제만 보기를 섞어 개념별로 저장해요. source: gemini · claude · chatgpt
     * 검수기가 켜져 있으면 근거 검증을 통과한 문제도 verified = false 로 두고 pending 으로 돌려줘요. check() 로 마저 검수해요.
     */
    fun save(material: Material, generated: List<GeneratedQuestion>, source: String): SaveResult {
        val pageText = material.pages.associate { it.page to it.text }
        val valid = generated.filter { isWellFormed(it, pageText.keys) }.filter { q ->
            val why = ruleViolation(q) ?: return@filter true
            log.info("출제 규칙 탈락: fileId={}, {}, stem={}", material.fileId, why, q.stem.take(40))
            false
        }
        if (valid.isEmpty()) return SaveResult(0, 0, emptyList())
        val pending = mutableListOf<Long>()
        var grounded = 0
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

                questions.forEach { raw ->
                    val q = shuffle(raw, random)
                    val ok = isGrounded(q, pageText.getValue(q.evidencePage))
                    if (ok) grounded++
                    val id = jdbc.sql(
                        """
                        insert into questions (course_id, concept_id, scope, stem, choices, answer_idx, explanation,
                                               evidence_file_id, evidence_page, source, verified)
                        values (:courseId, :conceptId, :scope, :stem, cast(:choices as jsonb), :answer, :explanation,
                                :fileId, :page, :source, :verified)
                        returning id
                        """.trimIndent(),
                    ).param("courseId", material.courseId).param("conceptId", conceptId).param("scope", material.scope)
                        .param("stem", q.stem.trim()).param("choices", json.writeValueAsString(q.choices.map { it.trim() }))
                        .param("answer", q.answerIdx).param("explanation", q.explanation.trim())
                        .param("fileId", material.fileId).param("page", q.evidencePage).param("source", source)
                        .param("verified", ok && !checker.enabled)
                        .query(Long::class.java).single()
                    if (ok && checker.enabled) pending += id
                }
            }
        }
        return SaveResult(valid.size, grounded, pending)
    }

    /**
     * 정답 숨긴 검수 (#52): 문제 · 보기 · 자료만 검수기에 보내고, 맞는 보기가 정확히 하나이며 저장된 정답과 같으면 verified 로 바꿔요.
     * 호출이 실패하면 verified = false 로 남겨요. 출제하지 않고 보충(P-BIT-02) 때 새 문제로 채워요.
     */
    fun check(material: Material, ids: List<Long>): Int {
        if (ids.isEmpty() || !checker.enabled) return 0
        val rows = jdbc.sql("select id, stem, choices::text as choices, answer_idx from questions where id in (:ids)").param("ids", ids)
            .query { rs, _ ->
                CheckItem(rs.getLong("id"), rs.getString("stem"), json.readTree(rs.getString("choices")).toList().map { it.asString() }) to
                    rs.getInt("answer_idx")
            }.list()
        val verdicts = try {
            checker.check(material.pages, rows.map { it.first })
        } catch (e: Exception) {
            log.warn("정답 숨긴 검수 실패: fileId={}, {}문제는 출제하지 않아요", material.fileId, rows.size, e)
            return 0
        }
        val passed = rows.filter { (item, answer) -> passes(verdicts[item.id], answer) }.map { it.first.id }
        rows.filterNot { it.first.id in passed }.forEach { (item, answer) ->
            log.info("정답 숨긴 검수 탈락: questionId={}, 정답={}, 판정={}", item.id, answer, verdicts[item.id]?.map { it.matches })
        }
        if (passed.isNotEmpty()) jdbc.sql("update questions set verified = true where id in (:ids)").param("ids", passed).update()
        return passed.size
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun onSaved(event: QuestionsSavedEvent) {
        val material = material(event.fileId) ?: return
        runCatching { check(material, event.ids) }.onFailure { log.warn("정답 숨긴 검수 실패: fileId={}", event.fileId, it) }
    }

    /** 보기 4개(중복 없음) · 정답 번호 0~3 · 있는 페이지를 근거로 든 문제만 */
    fun isWellFormed(q: GeneratedQuestion, pages: Set<Int>): Boolean =
        q.concept.isNotBlank() && q.stem.isNotBlank() && q.explanation.isNotBlank() &&
            q.choices.size == 4 && q.choices.all { it.isNotBlank() } && q.choices.map { it.replace(WHITESPACE, "") }.toSet().size == 4 &&
            q.answerIdx in 0..3 && q.evidencePage in pages

    /**
     * 근거 검증: 모델이 옮긴 근거 문장이 근거 페이지 본문에 실제로 있는지 봐요.
     * PDF 추출과 모델 출력의 줄바꿈 · 공백이 달라서 공백을 모두 지우고 비교해요.
     */
    fun isGrounded(q: GeneratedQuestion, pageText: String): Boolean {
        val quote = normalize(q.evidenceQuote)
        return quote.length >= MIN_QUOTE && normalize(pageText).contains(quote)
    }

    private fun normalize(s: String) = s.lowercase().replace(WHITESPACE, "")

    /** 입력이 너무 길면 앞에서부터 예산까지만 보내요 */
    private fun trimToBudget(pages: List<PageText>): List<PageText> {
        var total = 0
        return pages.takeWhile { total += it.text.length; total <= MAX_CHARS }
            .ifEmpty { listOf(pages.first().copy(text = pages.first().text.take(MAX_CHARS))) }
    }

    companion object {
        private val WHITESPACE = Regex("\\s+")
        private const val MIN_QUOTE = 10
        private const val MAX_CHARS = 80_000
    }
}

/**
 * 보기를 섞고 정답 번호를 같이 옮겨요 (#52). 모델이 정답을 한 위치(주로 0번)에 몰아 두기 때문이에요.
 * newChoices[i] = choices[order[i]], 새 정답 번호 = order 에서 옛 정답 번호의 위치
 */
fun shuffle(q: GeneratedQuestion, random: Random): GeneratedQuestion = reorder(q, q.choices.indices.shuffled(random))

fun reorder(q: GeneratedQuestion, order: List<Int>): GeneratedQuestion {
    require(order.sorted() == q.choices.indices.toList()) { "order 는 보기 번호를 한 번씩 담아야 해요" }
    return q.copy(choices = order.map { q.choices[it] }, answerIdx = order.indexOf(q.answerIdx))
}
