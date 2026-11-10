package app.daybit.explore

import app.daybit.course.Visibility
import app.daybit.global.common.PageResponse
import app.daybit.global.error.ApiException
import app.daybit.global.error.ErrorCode
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.sql.ResultSet

@Service
class ExploreService(private val jdbc: JdbcClient) {

    /**
     * 목록 한 줄에 필요한 것: 아이콘 · 과목명 · 관리자 닉네임 + 내 상태 (P-EXP-05).
     * 구독자 수는 정렬에만 쓰고 응답에는 넣지 않아요.
     */
    private val itemSelect = """
        select c.id, c.code, c.name, c.icon, c.term, c.visibility, c.intro, c.state,
               a.nickname as admin,
               coalesce(me.status, 'none') as my_status, me.role as my_role
          from courses c
          join course_members am on am.course_id = c.id and am.role = 'admin'
          join users a on a.id = am.user_id
          left join course_members me on me.course_id = c.id and me.user_id = :userId
    """.trimIndent()

    /** P-EXP-01, 02: 종료되지 않은 공개 과목. 인기순(구독자 수) 또는 최신순, 페이지는 1부터 */
    @Transactional(readOnly = true)
    fun list(userId: Long, sort: ExploreSort, page: Int, size: Int): PageResponse<ExploreItem> {
        val where = "where c.visibility = 'public' and c.state = 'active'"
        val order = when (sort) {
            ExploreSort.subscribers ->
                "(select count(*) from course_members s where s.course_id = c.id and s.status = 'active') desc, c.created_at desc, c.id desc"
            ExploreSort.newest -> "c.created_at desc, c.id desc"
        }
        val items = jdbc.sql("$itemSelect $where order by $order limit :size offset :offset")
            .param("userId", userId)
            .param("size", size)
            .param("offset", (page - 1).toLong() * size)
            .query { rs, _ -> rs.toItem() }
            .list()
        val total = jdbc.sql("select count(*) from courses c $where").query(Long::class.java).single()
        return PageResponse(items, total, page, size)
    }

    /**
     * P-EXP-03: 과목 코드 전체 일치(대소문자 무시, 비공개·종료 포함)를 맨 앞에,
     * 그다음 과목명 부분 일치(공개 · 진행 중만)를 최신순으로 보여줘요.
     */
    @Transactional(readOnly = true)
    fun search(userId: Long, q: String): List<ExploreItem> {
        val keyword = q.trim()
        if (keyword.isEmpty()) return emptyList()
        return jdbc.sql(
            """
            $itemSelect
             where c.code = upper(:keyword)
                or (c.visibility = 'public' and c.state = 'active' and c.name ilike :pattern escape '\')
             order by (c.code = upper(:keyword)) desc, c.created_at desc, c.id desc
             limit :limit
            """.trimIndent(),
        )
            .param("userId", userId)
            .param("keyword", keyword)
            .param("pattern", "%" + keyword.escapeLike() + "%")
            .param("limit", SEARCH_LIMIT)
            .query { rs, _ -> rs.toItem() }
            .list()
    }

    /** P-COURSE-06: 코드로 과목 정보를 봐요. 비공개·종료 과목도 코드를 알면 볼 수 있어요. */
    @Transactional(readOnly = true)
    fun info(userId: Long, code: String): CourseInfo =
        jdbc.sql("$itemSelect where c.code = upper(:code)")
            .param("userId", userId).param("code", code.trim())
            .query { rs, _ ->
                CourseInfo(
                    courseId = rs.getLong("id"),
                    code = rs.getString("code"),
                    name = rs.getString("name"),
                    icon = rs.getString("icon"),
                    admin = rs.getString("admin"),
                    term = rs.getString("term"),
                    visibility = Visibility.valueOf(rs.getString("visibility")),
                    intro = rs.getString("intro"),
                    ended = rs.getString("state") == "ended",
                    myStatus = rs.myStatus(),
                )
            }
            .optional().orElseThrow { ApiException(ErrorCode.NOT_FOUND) }

    /**
     * P-COURSE-03: 공개면 바로 구독, 비공개면 가입 요청(대기 중).
     * P-EXP-04: 종료된 과목은 409. 이미 구독·요청한 상태면 지금 상태를 그대로 돌려줘요.
     */
    @Transactional
    fun subscribe(userId: Long, code: String): SubscribeResponse {
        val (courseId, visibility, ended) = jdbc.sql("select id, visibility, state from courses where code = upper(:code)")
            .param("code", code.trim())
            .query { rs, _ -> Triple(rs.getLong("id"), Visibility.valueOf(rs.getString("visibility")), rs.getString("state") == "ended") }
            .optional().orElseThrow { ApiException(ErrorCode.NOT_FOUND) }

        current(courseId, userId)?.let { return SubscribeResponse(it, courseId) }
        if (ended) throw ApiException(ErrorCode.COURSE_ENDED)

        val status = if (visibility == Visibility.public) "active" else "pending"
        jdbc.sql(
            """
            insert into course_members (course_id, user_id, role, status, joined_at, requested_at)
            values (:courseId, :userId, 'member', :status,
                    case when :status = 'active' then now() end,
                    case when :status = 'pending' then now() end)
            on conflict (course_id, user_id) do nothing
            """.trimIndent(),
        ).param("courseId", courseId).param("userId", userId).param("status", status).update()

        // 동시에 두 번 눌러도 같은 결과가 나오게 저장된 상태를 다시 읽어요
        return SubscribeResponse(current(courseId, userId)!!, courseId)
    }

    private fun current(courseId: Long, userId: Long): SubscribeResult? =
        jdbc.sql("select status from course_members where course_id = :courseId and user_id = :userId")
            .param("courseId", courseId).param("userId", userId)
            .query(String::class.java).optional().orElse(null)
            ?.let { if (it == "active") SubscribeResult.subscribed else SubscribeResult.pending }

    private fun ResultSet.toItem() = ExploreItem(
        code = getString("code"),
        name = getString("name"),
        icon = getString("icon"),
        admin = getString("admin"),
        ended = getString("state") == "ended",
        myStatus = myStatus(),
    )

    // 관리자 · 공동 관리자는 탐색에서 "구독 중" 대신 "관리 중"으로 보여요
    private fun ResultSet.myStatus() = when (getString("my_status")) {
        "active" -> if (getString("my_role") in setOf("admin", "co")) MyStatus.managing else MyStatus.subscribed
        "pending" -> MyStatus.pending
        else -> MyStatus.none
    }

    private fun String.escapeLike() = replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    companion object {
        private const val SEARCH_LIMIT = 50
    }
}
