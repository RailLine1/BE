package app.daybit.course

import app.daybit.user.MembershipCounter
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component

/** 내 정보의 "구독 N개 · 관리 N개" (종료되지 않은 과목, 승인된 멤버십만) */
@Component
class CourseMembershipCounter(private val jdbc: JdbcClient) : MembershipCounter {
    override fun count(userId: Long): Pair<Int, Int> {
        val row = jdbc.sql(
            """
            select count(*)                                   as subscribed,
                   count(*) filter (where m.role in ('admin', 'co')) as managed
              from course_members m
              join courses c on c.id = m.course_id
             where m.user_id = :userId and m.status = 'active' and c.state = 'active'
            """.trimIndent(),
        ).param("userId", userId)
            .query { rs, _ -> rs.getInt("subscribed") to rs.getInt("managed") }
            .single()
        return row
    }
}
