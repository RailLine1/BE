package app.daybit.course

import app.daybit.global.common.CourseRole
import app.daybit.global.common.MyAi
import app.daybit.global.error.ApiException
import app.daybit.global.error.ErrorCode
import app.daybit.global.storage.FileStorage
import app.daybit.global.storage.deleteAfterCommit
import app.daybit.notification.NotificationType
import app.daybit.notification.Notifier
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.SecureRandom
import java.sql.ResultSet
import java.time.LocalDate

@Service
class CourseService(
    private val jdbc: JdbcClient,
    private val notifier: Notifier,
    private val storage: FileStorage,
) {

    // ---------- 만들기 ----------

    /** P-COURSE-01, 02 · P-MAT-01: 과목 + 관리자 멤버십 + 기본 폴더 mid/final */
    @Transactional
    fun create(userId: Long, req: CreateCourseRequest): CreateCourseResponse {
        validateExamDates(req.midExamDate, req.finalExamDate)
        val sourceAi = resolveQuizSource(userId, req.quizSource)

        val (id, code) = insertCourseWithUniqueCode(userId, req, sourceAi)

        jdbc.sql(
            """
            insert into course_members (course_id, user_id, role, status, joined_at)
            values (:courseId, :userId, 'admin', 'active', now())
            """.trimIndent(),
        ).param("courseId", id).param("userId", userId).update()

        jdbc.sql(
            """
            insert into folders (course_id, name, kind, sort)
            values (:courseId, 'mid', 'mid', 0), (:courseId, 'final', 'final', 1)
            """.trimIndent(),
        ).param("courseId", id).update()

        return CreateCourseResponse(id, code)
    }

    private fun insertCourseWithUniqueCode(userId: Long, req: CreateCourseRequest, sourceAi: MyAi?): Pair<Long, String> {
        // Postgres 는 트랜잭션 안에서 unique 위반이 나면 트랜잭션 전체가 깨지므로, 미리 비어 있는 코드를 골라요.
        // (36^6 ≈ 21억이라 겹칠 일은 거의 없고, 동시 생성으로 겹치면 unique 제약이 막아 줘요)
        val code = generateSequence { newCode() }.take(CODE_ATTEMPTS)
            .firstOrNull { candidate ->
                jdbc.sql("select count(*) from courses where code = :code").param("code", candidate)
                    .query(Int::class.java).single() == 0
            } ?: throw ApiException(ErrorCode.INTERNAL_ERROR)

        val id = jdbc.sql(
            """
            insert into courses (code, name, icon, intro, term, visibility, mid_exam_date, final_exam_date,
                                 quiz_source_type, quiz_source_user_id, quiz_source_ai, created_by)
            values (:code, :name, :icon, :intro, :term, :visibility, :mid, :final,
                    :sourceType, :sourceUser, :sourceAi, :userId)
            returning id
            """.trimIndent(),
        )
            .param("code", code)
            .param("name", req.name.trim())
            .param("icon", req.icon)
            .param("intro", req.intro?.trim()?.ifEmpty { null })
            .param("term", req.term?.trim()?.ifEmpty { null })
            .param("visibility", req.visibility.name)
            .param("mid", req.midExamDate)
            .param("final", req.finalExamDate)
            .param("sourceType", if (sourceAi == null) "default" else "myai")
            .param("sourceUser", if (sourceAi == null) null else userId)
            .param("sourceAi", sourceAi?.name)
            .param("userId", userId)
            .query(Long::class.java)
            .single()
        return id to code
    }

    // ---------- 조회 ----------

    /** P-COURSE-05: 승인 대기 중인 과목은 빼요 */
    @Transactional(readOnly = true)
    fun mine(userId: Long, state: CourseState): List<CourseTile> =
        jdbc.sql(
            """
            select c.id, c.name, c.icon, c.intro
              from course_members m
              join courses c on c.id = m.course_id
             where m.user_id = :userId and m.status = 'active' and c.state = :state
             order by m.joined_at, c.id
            """.trimIndent(),
        ).param("userId", userId).param("state", state.name)
            .query { rs, _ -> CourseTile(rs.getLong("id"), rs.getString("name"), rs.getString("icon"), rs.getString("intro")) }
            .list()

    @Transactional(readOnly = true)
    fun detail(userId: Long, courseId: Long): CourseDetail {
        val m = membership(userId, courseId)
        return jdbc.sql(
            """
            select c.id, c.name, c.icon, c.intro, c.term, c.raw_allowed, c.state,
                   (select count(*) from course_members x where x.course_id = c.id and x.status = 'active') as subscribers,
                   (select count(*) from bits b where b.course_id = c.id and b.archived_at is not null)     as archive_count
              from courses c
             where c.id = :courseId
            """.trimIndent(),
        ).param("courseId", courseId)
            .query { rs, _ ->
                CourseDetail(
                    courseId = rs.getLong("id"),
                    name = rs.getString("name"),
                    icon = rs.getString("icon"),
                    intro = rs.getString("intro"),
                    term = rs.getString("term"),
                    subscribers = rs.getInt("subscribers"),
                    role = m.role,
                    rawAllowed = rs.getBoolean("raw_allowed"),
                    archiveCount = rs.getInt("archive_count"),
                    state = CourseState.valueOf(rs.getString("state")),
                )
            }.single()
    }

    /** P-ROLE-01: 멤버는 코드 · 권한 · 알림만 */
    @Transactional(readOnly = true)
    fun settings(userId: Long, courseId: Long): CourseSettings {
        val m = membership(userId, courseId)
        val c = course(courseId)
        if (m.role == CourseRole.member) return CourseSettings(code = c.code, role = m.role, notify = m.notify)
        return CourseSettings(
            code = c.code,
            role = m.role,
            notify = m.notify,
            icon = c.icon,
            intro = c.intro,
            visibility = c.visibility,
            midExamDate = c.midExamDate,
            finalExamDate = c.finalExamDate,
            rawAllowed = c.rawAllowed,
            quizSource = QuizSource(c.quizSourceType, c.quizSourceAi),
        )
    }

    // ---------- 변경 ----------

    /** P-COURSE-04, P-BIT-03, P-BIT-14 · 관리자·공동 관리자만 */
    @Transactional
    fun update(userId: Long, courseId: Long, req: UpdateCourseRequest): CourseSettings {
        val m = membership(userId, courseId, lock = true)
        if (m.role == CourseRole.member) throw ApiException(ErrorCode.FORBIDDEN)
        val c = course(courseId)

        // 비울 수 있는 칸은 키가 있으면 null 이어도 반영해요 (#44)
        val mid = if ("midExamDate" in req.sent) req.midExamDate else c.midExamDate
        val final = if ("finalExamDate" in req.sent) req.finalExamDate else c.finalExamDate
        validateExamDates(mid, final)

        val sets = mutableListOf<String>()
        val params = mutableMapOf<String, Any?>("courseId" to courseId)
        req.icon?.let { sets += "icon = :icon"; params["icon"] = it }
        if ("intro" in req.sent) { sets += "intro = :intro"; params["intro"] = req.intro?.trim()?.ifEmpty { null } }
        req.visibility?.let { sets += "visibility = :visibility"; params["visibility"] = it.name }
        if ("midExamDate" in req.sent) { sets += "mid_exam_date = :mid"; params["mid"] = mid }
        if ("finalExamDate" in req.sent) { sets += "final_exam_date = :final"; params["final"] = final }
        req.rawAllowed?.let { sets += "raw_allowed = :raw"; params["raw"] = it }
        req.quizSource?.let { qs ->
            val ai = resolveQuizSource(userId, qs)
            sets += "quiz_source_type = :sourceType"
            sets += "quiz_source_user_id = :sourceUser"
            sets += "quiz_source_ai = :sourceAi"
            params["sourceType"] = if (ai == null) "default" else "myai"
            params["sourceUser"] = if (ai == null) null else userId
            params["sourceAi"] = ai?.name
        }
        if (sets.isNotEmpty()) {
            jdbc.sql("update courses set ${sets.joinToString()} where id = :courseId").params(params).update()
        }

        // P-COURSE-04: 비공개 → 공개면 대기 중인 가입 요청을 모두 승인하고 알려요
        if (c.visibility == Visibility.private && req.visibility == Visibility.public) {
            val approved = jdbc.sql(
                """
                update course_members set status = 'active', joined_at = now()
                 where course_id = :courseId and status = 'pending'
                returning user_id
                """.trimIndent(),
            ).param("courseId", courseId).query(Long::class.java).list().filterNotNull()
            notifier.notify(approved, NotificationType.join_approved, mapOf("courseId" to courseId, "courseName" to c.name))
        }

        return settings(userId, courseId)
    }

    /** P-NOTI-02 */
    @Transactional
    fun subscription(userId: Long, courseId: Long, notify: Boolean) {
        membership(userId, courseId)
        jdbc.sql("update course_members set notify = :notify where course_id = :courseId and user_id = :userId")
            .param("notify", notify).param("courseId", courseId).param("userId", userId).update()
    }

    // ---------- 탈퇴 ----------

    /** P-ROLE-03, 04 · P-BIT-15 · P-DATA-02 */
    @Transactional
    fun leave(userId: Long, courseId: Long): LeaveResponse {
        val m = membership(userId, courseId, lock = true)
        val c = course(courseId)

        if (m.role == CourseRole.admin) {
            val successor = jdbc.sql(
                """
                select m.user_id, u.nickname
                  from course_members m join users u on u.id = m.user_id
                 where m.course_id = :courseId and m.role = 'co' and m.status = 'active'
                 order by m.co_since nulls last, m.joined_at
                 limit 1
                """.trimIndent(),
            ).param("courseId", courseId)
                .query { rs, _ -> rs.getLong("user_id") to rs.getString("nickname") }
                .optional().orElse(null)

            if (successor == null) {
                // P-ROLE-04: 공동 관리자가 없으면 과목과 모든 자료를 지워요 (DB 는 FK cascade, 원본은 커밋 뒤에)
                val keys = jdbc.sql("select storage_key from files where course_id = :courseId").param("courseId", courseId)
                    .query(String::class.java).list().filterNotNull()
                storage.deleteAfterCommit(keys)
                jdbc.sql("delete from courses where id = :courseId").param("courseId", courseId).update()
                return LeaveResponse(LeaveResult.deleted)
            }

            val (newAdminId, newAdminNickname) = successor
            // 관리자는 과목당 1명(course_members_one_admin_uq)이라 먼저 빠진 뒤에 승격해요
            removeMember(courseId, userId)
            jdbc.sql("update course_members set role = 'admin' where course_id = :courseId and user_id = :userId")
                .param("courseId", courseId).param("userId", newAdminId).update()
            notifier.notify(listOf(newAdminId), NotificationType.promoted_admin, mapOf("courseId" to courseId, "courseName" to c.name))
            return LeaveResponse(LeaveResult.promoted, newAdminNickname)
        }

        removeMember(courseId, userId)
        return LeaveResponse(LeaveResult.left)
    }

    /** 멤버십을 지우고, 그 사람이 퀴즈 출처였다면 Gemini 로 되돌려요 (P-BIT-15). 탈퇴·추방에서 같이 써요. */
    fun removeMember(courseId: Long, userId: Long) {
        jdbc.sql("delete from course_members where course_id = :courseId and user_id = :userId")
            .param("courseId", courseId).param("userId", userId).update()
        releaseQuizSource(courseId, userId)
    }

    /**
     * userId 가 이 과목의 퀴즈 출처(출제 AI)라면 Gemini 로 되돌리고 관리자·공동 관리자에게 알려요 (P-BIT-15).
     * 탈퇴·추방·멤버로 강등처럼 그 사람이 더 이상 출처를 맡을 수 없을 때 불러요.
     */
    fun releaseQuizSource(courseId: Long, userId: Long) {
        val c = course(courseId)
        if (c.quizSourceType != QuizSourceType.myai || c.quizSourceUserId != userId) return
        jdbc.sql(
            """
            update courses set quiz_source_type = 'default', quiz_source_user_id = null, quiz_source_ai = null
             where id = :courseId
            """.trimIndent(),
        ).param("courseId", courseId).update()
        val managers = jdbc.sql(
            "select user_id from course_members where course_id = :courseId and status = 'active' and role in ('admin', 'co')",
        ).param("courseId", courseId).query(Long::class.java).list().filterNotNull()
        notifier.notify(managers, NotificationType.quiz_source_fallback, mapOf("courseId" to courseId, "courseName" to c.name))
    }

    // ---------- 공통 ----------

    data class Membership(val role: CourseRole, val notify: Boolean)

    /**
     * 승인된 멤버십을 찾아요. 과목이 없거나 멤버가 아니면(대기 중 포함) 404 —
     * 비공개 과목이 있는지조차 드러나지 않게 둘을 구분하지 않아요 (API 명세서 0. 공통).
     * 멤버이지만 권한이 모자란 경우의 403 은 각 기능에서 따로 던져요.
     * lock = true 면 과목 행을 잠가서 탈퇴·권한 변경이 동시에 일어나도 꼬이지 않게 해요.
     */
    fun membership(userId: Long, courseId: Long, lock: Boolean = false): Membership {
        val exists = jdbc.sql("select id from courses where id = :courseId" + if (lock) " for update" else "")
            .param("courseId", courseId).query(Long::class.java).optional().isPresent
        if (!exists) throw ApiException(ErrorCode.NOT_FOUND)
        return jdbc.sql(
            "select role, notify from course_members where course_id = :courseId and user_id = :userId and status = 'active'",
        ).param("courseId", courseId).param("userId", userId)
            .query { rs, _ -> Membership(CourseRole.valueOf(rs.getString("role")), rs.getBoolean("notify")) }
            .optional().orElseThrow { ApiException(ErrorCode.NOT_FOUND) }
    }

    private data class CourseRow(
        val code: String,
        val name: String,
        val icon: String,
        val intro: String?,
        val visibility: Visibility,
        val midExamDate: LocalDate?,
        val finalExamDate: LocalDate?,
        val rawAllowed: Boolean,
        val quizSourceType: QuizSourceType,
        val quizSourceUserId: Long?,
        val quizSourceAi: MyAi?,
    )

    private fun course(courseId: Long): CourseRow =
        jdbc.sql("select * from courses where id = :courseId").param("courseId", courseId)
            .query { rs, _ -> rs.toCourseRow() }
            .optional().orElseThrow { ApiException(ErrorCode.NOT_FOUND) }

    private fun ResultSet.toCourseRow() = CourseRow(
        code = getString("code"),
        name = getString("name"),
        icon = getString("icon"),
        intro = getString("intro"),
        visibility = Visibility.valueOf(getString("visibility")),
        midExamDate = getObject("mid_exam_date", LocalDate::class.java),
        finalExamDate = getObject("final_exam_date", LocalDate::class.java),
        rawAllowed = getBoolean("raw_allowed"),
        quizSourceType = QuizSourceType.valueOf(getString("quiz_source_type")),
        quizSourceUserId = getObject("quiz_source_user_id", java.lang.Long::class.java)?.toLong(),
        quizSourceAi = getString("quiz_source_ai")?.let { MyAi.valueOf(it) },
    )

    /**
     * P-AI-09: 출처는 Gemini(default) 또는 **본인이** 내 AI 출제에 등록한 AI만.
     * myai 면 고른 AI 를, default 면 null 을 돌려줘요.
     */
    private fun resolveQuizSource(userId: Long, source: QuizSource): MyAi? {
        if (source.type == QuizSourceType.default) return null
        val ai = source.ai ?: throw ApiException(ErrorCode.VALIDATION_FAILED, "출제 AI를 골라 주세요.")
        // P-AI-08: 예약 실행(get_jobs)이 한 번이라도 들어온 AI 만 등록된 것으로 봐요
        val ready = jdbc.sql("select count(*) from myai_runs where user_id = :userId and ai = :ai")
            .param("userId", userId).param("ai", ai.name).query(Int::class.java).single() > 0
        if (!ready) throw ApiException(ErrorCode.QUIZ_SOURCE_UNAVAILABLE)
        return ai
    }

    private fun validateExamDates(mid: LocalDate?, final: LocalDate?) {
        if (mid != null && final != null && final.isBefore(mid)) {
            throw ApiException(ErrorCode.VALIDATION_FAILED, "기말 시험일은 중간 시험일보다 뒤여야 해요.")
        }
    }

    private fun newCode(): String = buildString(CODE_LENGTH) {
        repeat(CODE_LENGTH) { append(CODE_CHARS[random.nextInt(CODE_CHARS.length)]) }
    }

    companion object {
        private const val CODE_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789" // P-COURSE-02
        private const val CODE_LENGTH = 6
        private const val CODE_ATTEMPTS = 10
        private val random = SecureRandom()
    }
}
