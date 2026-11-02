package app.daybit.archive

import app.daybit.course.CourseService
import app.daybit.global.common.CourseRole
import app.daybit.global.common.Evidence
import app.daybit.global.common.PageResponse
import app.daybit.global.error.ApiException
import app.daybit.global.error.ErrorCode
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.json.JsonMapper
import java.time.OffsetDateTime

@Service
class ArchiveService(
    private val jdbc: JdbcClient,
    private val courses: CourseService,
    private val json: JsonMapper,
) {

    /** P-ARC-01, 02: 마감된 Bit 만, 최근 것부터 */
    @Transactional(readOnly = true)
    fun archive(userId: Long, courseId: Long, page: Int, size: Int): PageResponse<ArchivePost> {
        courses.membership(userId, courseId)
        val total = jdbc.sql("select count(*) from bits where course_id = :c and archived_at is not null")
            .param("c", courseId).query(Long::class.java).single()
        val items = jdbc.sql(
            """
            select b.id, b.no, b.archived_at, b.attempts, b.accuracy, b.average_sec,
                   q.stem, q.choices::text as choices, q.answer_idx, q.explanation, q.evidence_file_id, q.evidence_page,
                   coalesce(f.title, '') as file_title,
                   (select count(*) from memos m where m.bit_id = b.id and m.deleted_at is null) as memo_count
              from bits b
              join questions q on q.id = b.question_id
              left join files f on f.id = q.evidence_file_id
             where b.course_id = :c and b.archived_at is not null
             order by b.open_date desc, b.id desc
             limit :size offset :offset
            """.trimIndent(),
        ).param("c", courseId).param("size", size).param("offset", (page - 1) * size)
            .query { rs, _ ->
                ArchivePost(
                    bitId = rs.getLong("id"),
                    no = rs.getInt("no"),
                    archivedAt = rs.getObject("archived_at", OffsetDateTime::class.java),
                    stem = rs.getString("stem"),
                    choices = json.readTree(rs.getString("choices")).toList().map { it.asString() },
                    answer = rs.getInt("answer_idx"),
                    explanation = rs.getString("explanation"),
                    evidence = Evidence(
                        fileId = rs.getObject("evidence_file_id")?.let { (it as Number).toLong() },
                        file = rs.getString("file_title"),
                        page = rs.getInt("evidence_page"),
                    ),
                    stats = ArchiveStats(rs.getInt("attempts"), rs.getDouble("accuracy"), rs.getInt("average_sec")),
                    memoCount = rs.getInt("memo_count"),
                )
            }.list()
        return PageResponse(items, total, page, size)
    }

    /** P-ARC-03: 작성자 정보 없이 내용과 mine 만, 쓴 순서대로 */
    @Transactional(readOnly = true)
    fun memos(userId: Long, bitId: Long): List<MemoItem> {
        archivedBit(userId, bitId)
        return jdbc.sql("select id, body, user_id from memos where bit_id = :b and deleted_at is null order by created_at, id")
            .param("b", bitId)
            .query { rs, _ -> MemoItem(rs.getLong("id"), rs.getString("body"), rs.getLong("user_id") == userId) }
            .list()
    }

    @Transactional
    fun add(userId: Long, bitId: Long, body: String): MemoItem {
        archivedBit(userId, bitId)
        val text = clean(body)
        val id = jdbc.sql("insert into memos (bit_id, user_id, body) values (:b, :u, :body) returning id")
            .param("b", bitId).param("u", userId).param("body", text).query(Long::class.java).single()
        return MemoItem(id, text, true)
    }

    /** P-ARC-04: 수정은 작성자만 */
    @Transactional
    fun edit(userId: Long, memoId: Long, body: String): MemoItem {
        val memo = memo(userId, memoId)
        if (memo.authorId != userId) throw ApiException(ErrorCode.FORBIDDEN)
        val text = clean(body)
        jdbc.sql("update memos set body = :body, updated_at = now() where id = :id").param("body", text).param("id", memoId).update()
        return MemoItem(memoId, text, true)
    }

    /** P-ARC-04: 삭제는 작성자와 관리자 · 공동 관리자. 표시만 하고 지운 사람을 남겨요 */
    @Transactional
    fun delete(userId: Long, memoId: Long) {
        val memo = memo(userId, memoId)
        if (memo.authorId != userId && memo.role == CourseRole.member) throw ApiException(ErrorCode.FORBIDDEN)
        jdbc.sql("update memos set deleted_at = now(), deleted_by = :u where id = :id").param("u", userId).param("id", memoId).update()
    }

    // ---------- 공통 ----------

    /** 멤버가 아니거나 아직 마감되지 않은 Bit 면 404. 내 권한을 돌려줘요 */
    private fun archivedBit(userId: Long, bitId: Long): CourseRole {
        val courseId = jdbc.sql("select course_id from bits where id = :id and archived_at is not null").param("id", bitId)
            .query(Long::class.java).optional().orElseThrow { ApiException(ErrorCode.NOT_FOUND) }
        return courses.membership(userId, courseId).role
    }

    private data class MemoRow(val authorId: Long, val role: CourseRole)

    /** 지워진 메모는 404 */
    private fun memo(userId: Long, memoId: Long): MemoRow {
        val (bitId, authorId) = jdbc.sql("select bit_id, user_id from memos where id = :id and deleted_at is null").param("id", memoId)
            .query { rs, _ -> rs.getLong("bit_id") to rs.getLong("user_id") }
            .optional().orElseThrow { ApiException(ErrorCode.NOT_FOUND) }
        return MemoRow(authorId, archivedBit(userId, bitId))
    }

    private fun clean(body: String): String =
        body.trim().ifEmpty { throw ApiException(ErrorCode.VALIDATION_FAILED, "body: 메모를 입력해 주세요.") }
}
