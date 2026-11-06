package app.daybit.dev

import app.daybit.bit.BitBatchService
import app.daybit.bit.BitDays
import app.daybit.global.common.CourseRole
import app.daybit.course.CourseService
import app.daybit.global.config.ClockConfig.Companion.SEOUL
import app.daybit.global.error.ApiException
import app.daybit.global.error.ErrorCode
import app.daybit.global.security.LoginUser
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.time.LocalDate
import java.time.ZonedDateTime

data class DevBitResponse(
    val bitId: Long,
    val no: Int,
    @field:Schema(description = "Bit 날짜. 17시 전에는 어제 날짜예요(BitDays)")
    val openDate: LocalDate,
    @field:Schema(description = "이번 요청으로 새로 만들었으면 true, 이미 있던 Bit 면 false")
    val created: Boolean,
)

/**
 * 개발 · 시연용 (#42). DAYBIT_DEV_TOOLS=true 일 때만 빈이 생겨요. 꺼져 있으면 경로가 없어 404 예요.
 * 정책서 규칙은 바꾸지 않고, 17시를 기다리거나 하루 한 번 제한에 막히지 않게만 해 줘요.
 * daybit.dev-admins 에 적힌 아이디는 과목 권한과 상관없이 이 API 를 쓸 수 있어요 (#50). 일반 API 권한은 그대로예요.
 */
@Tag(name = "0. 개발자 전용", description = "개발 · 시연 때만 켜는 API예요. 앱의 일반 화면에서는 쓰지 않아요. 서버에 지정한 개발자 계정(기본 demo01)은 과목 권한 없이 모든 과목에서 쓸 수 있어요.")
@RestController
@RequestMapping("/api/v1/dev")
@ConditionalOnProperty(prefix = "daybit", name = ["dev-tools"], havingValue = "true")
class DevController(
    private val courses: CourseService,
    private val batch: BitBatchService,
    private val jdbc: JdbcClient,
    private val clock: Clock,
    @Value("\${daybit.dev-admins:}") private val devAdmins: List<String>,
) {
    private val admins = devAdmins.map { it.trim() }.filter { it.isNotEmpty() }

    @Operation(
        summary = "지금 Bit 바로 열기",
        description = "지금 열려 있어야 할 날짜(17시 전이면 어제)의 Bit 가 없으면 평소 규칙(P-BIT-03, 04, 06)으로 바로 만들어요. " +
            "이미 있으면 그대로 돌려줘요. 관리자 · 공동 관리자만. 낼 문제가 없으면 409 NO_QUESTION_READY",
    )
    @PostMapping("/courses/{courseId}/bit/open-now")
    fun openNow(@LoginUser userId: Long, @PathVariable courseId: Long): DevBitResponse {
        requireManager(userId, courseId)
        val now = ZonedDateTime.now(clock.withZone(SEOUL))
        val date = BitDays.current(now)
        val created = batch.prepareOne(courseId, date, now.toOffsetDateTime())
        return jdbc.sql("select id, no from bits where course_id = :c and open_date = :d")
            .param("c", courseId).param("d", date)
            .query { rs, _ -> DevBitResponse(rs.getLong("id"), rs.getInt("no"), date, created) }
            .optional().orElseThrow { ApiException(ErrorCode.NO_QUESTION_READY) }
    }

    @Operation(
        summary = "내 풀이 기록 지우기",
        description = "이 Bit 의 내 풀이를 지워 다시 시작할 수 있게 해요. 연속 일수 · 정답 수 같은 누적 기록은 되돌리지 않아요. 그 과목 멤버면 쓸 수 있어요",
    )
    @PostMapping("/bits/{bitId}/reset-me")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    fun resetMe(@LoginUser userId: Long, @PathVariable bitId: Long) {
        val courseId = courseOf(bitId)
        if (!isDevAdmin(userId)) courses.membership(userId, courseId)
        jdbc.sql("delete from bit_attempts where bit_id = :b and user_id = :u").param("b", bitId).param("u", userId).update()
    }

    @Operation(
        summary = "Bit 지우기",
        description = "점검 · 시연 흔적을 치워요. Bit 와 그 풀이 · 메모를 지우고(아카이브에서도 사라져요), 낸 문제는 다시 낼 수 있게 돌려놔요. " +
            "연속 일수 · 정답 수 같은 누적 기록은 되돌리지 않아요. 관리자 · 공동 관리자만",
    )
    @DeleteMapping("/bits/{bitId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    fun deleteBit(@LoginUser userId: Long, @PathVariable bitId: Long) {
        requireManager(userId, courseOf(bitId))
        val questionId = jdbc.sql("select question_id from bits where id = :id").param("id", bitId).query(Long::class.java).single()
        jdbc.sql("delete from bits where id = :id").param("id", bitId).update()   // 풀이 · 메모는 FK cascade
        jdbc.sql("update questions set used_at = null where id = :q").param("q", questionId).update()
        jdbc.sql(
            """
            update concepts set times_used = greatest(times_used - 1, 0)
             where id = (select concept_id from questions where id = :q)
            """.trimIndent(),
        ).param("q", questionId).update()
    }

    /** 관리자 · 공동 관리자만. 개발자 계정은 과목만 있으면 돼요 */
    private fun requireManager(userId: Long, courseId: Long) {
        if (isDevAdmin(userId)) {
            val exists = jdbc.sql("select count(*) from courses where id = :c").param("c", courseId).query(Int::class.java).single()
            if (exists == 0) throw ApiException(ErrorCode.NOT_FOUND)
            return
        }
        val role = courses.membership(userId, courseId).role
        if (role != CourseRole.admin && role != CourseRole.co) throw ApiException(ErrorCode.FORBIDDEN)
    }

    private fun isDevAdmin(userId: Long): Boolean =
        admins.isNotEmpty() &&
            jdbc.sql("select count(*) from users where id = :u and login_id in (:ids)")
                .param("u", userId).param("ids", admins).query(Int::class.java).single() > 0

    private fun courseOf(bitId: Long): Long =
        jdbc.sql("select course_id from bits where id = :id").param("id", bitId)
            .query(Long::class.java).optional().orElseThrow { ApiException(ErrorCode.NOT_FOUND) }
}
