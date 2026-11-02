package app.daybit.course

import app.daybit.global.common.CourseRole
import app.daybit.global.error.ApiException
import app.daybit.global.error.ErrorCode
import app.daybit.notification.NotificationType
import app.daybit.notification.Notifier
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime

/**
 * 멤버 관리 (P-ROLE-02, 05) + 가입 요청 처리 (P-COURSE-03, P-NOTI-03).
 * 가입 요청은 course_members 의 status = 'pending' 행이고, requestId 는 요청한 사람의 userId 예요.
 */
@Service
class MemberService(
    private val jdbc: JdbcClient,
    private val courses: CourseService,
    private val notifier: Notifier,
) {

    // ---------- 멤버 ----------

    /** P-ROLE-02: 관리자 → 공동 관리자(된 날짜순) → 멤버(들어온 순) */
    @Transactional(readOnly = true)
    fun members(userId: Long, courseId: Long): List<MemberItem> {
        requireManager(userId, courseId)
        return jdbc.sql(
            """
            select m.user_id, u.nickname, m.role, m.co_since
              from course_members m join users u on u.id = m.user_id
             where m.course_id = :courseId and m.status = 'active'
             order by case m.role when 'admin' then 0 when 'co' then 1 else 2 end,
                      m.co_since nulls last, m.joined_at, m.user_id
            """.trimIndent(),
        ).param("courseId", courseId)
            .query { rs, _ ->
                MemberItem(
                    userId = rs.getLong("user_id"),
                    nickname = rs.getString("nickname"),
                    role = CourseRole.valueOf(rs.getString("role")),
                    coSince = rs.getObject("co_since", OffsetDateTime::class.java),
                )
            }.list()
    }

    /** 멤버 ↔ 공동 관리자. 관리자만 할 수 있고, 관리자 자신은 바꿀 수 없어요. */
    @Transactional
    fun changeRole(userId: Long, courseId: Long, targetId: Long, role: AssignableRole): MemberItem {
        val me = courses.membership(userId, courseId, lock = true)
        if (me.role != CourseRole.admin) throw ApiException(ErrorCode.FORBIDDEN)
        val target = target(courseId, targetId)
        if (target == CourseRole.admin) throw ApiException(ErrorCode.FORBIDDEN, "관리자의 권한은 바꿀 수 없어요.")

        if (target.name != role.name) {
            jdbc.sql(
                """
                update course_members
                   set role = cast(:role as varchar),
                       co_since = case when cast(:role as varchar) = 'co' then now() end   -- P-ROLE-03 승계 순서
                 where course_id = :courseId and user_id = :targetId
                """.trimIndent(),
            ).param("role", role.name).param("courseId", courseId).param("targetId", targetId).update()
            // 멤버는 퀴즈 출처를 맡을 수 없으니 강등되면 Gemini 로 돌려요 (P-BIT-14, 15)
            if (role == AssignableRole.member) courses.releaseQuizSource(courseId, targetId)
        }
        return members(userId, courseId).first { it.userId == targetId }
    }

    /** P-ROLE-05: 관리자는 누구든, 공동 관리자는 멤버만 내보낼 수 있어요. 다시 오려면 새로 구독해야 해요. */
    @Transactional
    fun kick(userId: Long, courseId: Long, targetId: Long) {
        val me = courses.membership(userId, courseId, lock = true)
        if (targetId == userId) throw ApiException(ErrorCode.FORBIDDEN, "본인은 [탈퇴하기]로 나가 주세요.")
        val target = target(courseId, targetId)
        val allowed = when (me.role) {
            CourseRole.admin -> target != CourseRole.admin
            CourseRole.co -> target == CourseRole.member
            CourseRole.member -> false
        }
        if (!allowed) throw ApiException(ErrorCode.FORBIDDEN)
        courses.removeMember(courseId, targetId)
    }

    // ---------- 가입 요청 ----------

    @Transactional(readOnly = true)
    fun joinRequests(userId: Long, courseId: Long): List<JoinRequestItem> {
        requireManager(userId, courseId)
        return jdbc.sql(
            """
            select m.user_id, u.nickname, coalesce(m.requested_at, m.created_at) as requested_at
              from course_members m join users u on u.id = m.user_id
             where m.course_id = :courseId and m.status = 'pending'
             order by requested_at, m.user_id
            """.trimIndent(),
        ).param("courseId", courseId)
            .query { rs, _ ->
                JoinRequestItem(
                    requestId = rs.getLong("user_id"),
                    nickname = rs.getString("nickname"),
                    requestedAt = rs.getObject("requested_at", OffsetDateTime::class.java),
                )
            }.list()
    }

    /** 승인하면 요청한 사람에게 알려요 (P-NOTI-03) */
    @Transactional
    fun approve(userId: Long, courseId: Long, requestId: Long) {
        requireManager(userId, courseId, lock = true)
        val approved = approvePending(courseId, requestId)
        if (approved.isEmpty()) throw ApiException(ErrorCode.NOT_FOUND)
    }

    @Transactional
    fun reject(userId: Long, courseId: Long, requestId: Long) {
        requireManager(userId, courseId, lock = true)
        val deleted = jdbc.sql("delete from course_members where course_id = :courseId and user_id = :requestId and status = 'pending'")
            .param("courseId", courseId).param("requestId", requestId).update()
        if (deleted == 0) throw ApiException(ErrorCode.NOT_FOUND)
    }

    @Transactional
    fun approveAll(userId: Long, courseId: Long): ApproveAllResponse {
        requireManager(userId, courseId, lock = true)
        return ApproveAllResponse(approvePending(courseId, null).size)
    }

    /** requestId 가 null 이면 대기 중인 요청을 모두 승인해요. 승인된 userId 를 돌려줘요. */
    private fun approvePending(courseId: Long, requestId: Long?): List<Long> {
        val approved = jdbc.sql(
            """
            update course_members set status = 'active', joined_at = now()
             where course_id = :courseId and status = 'pending'
               and (cast(:requestId as bigint) is null or user_id = cast(:requestId as bigint))
            returning user_id
            """.trimIndent(),
        ).param("courseId", courseId).param("requestId", requestId)
            .query(Long::class.java).list().filterNotNull()
        if (approved.isNotEmpty()) {
            val name = jdbc.sql("select name from courses where id = :courseId").param("courseId", courseId)
                .query(String::class.java).single()
            notifier.notify(approved, NotificationType.join_approved, mapOf("courseId" to courseId, "courseName" to name))
        }
        return approved
    }

    // ---------- 공통 ----------

    private fun requireManager(userId: Long, courseId: Long, lock: Boolean = false) {
        if (courses.membership(userId, courseId, lock).role == CourseRole.member) throw ApiException(ErrorCode.FORBIDDEN)
    }

    /** 대상 멤버의 권한. 승인된 멤버가 아니면 404. */
    private fun target(courseId: Long, targetId: Long): CourseRole =
        jdbc.sql("select role from course_members where course_id = :courseId and user_id = :targetId and status = 'active'")
            .param("courseId", courseId).param("targetId", targetId)
            .query { rs, _ -> CourseRole.valueOf(rs.getString("role")) }
            .optional().orElseThrow { ApiException(ErrorCode.NOT_FOUND) }
}
