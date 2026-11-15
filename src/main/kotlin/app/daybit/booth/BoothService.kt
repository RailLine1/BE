package app.daybit.booth

import app.daybit.global.error.ApiException
import app.daybit.global.error.ErrorCode
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Duration
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit
import java.util.UUID

@Service
class BoothService(
    private val jdbc: JdbcClient,
    private val clock: Clock,
    json: JsonMapper,
) {
    data class Item(val stem: String, val choices: List<String>, val answer: Int, val explanation: String)

    /** 문제는 배포 파일(booth/questions.json)에 둬요. 모두 같은 문제를 같은 순서로 풀어요 */
    private val items: List<Item> = javaClass.getResourceAsStream("/booth/questions.json")!!.use { json.readValue(it, Array<Item>::class.java).toList() }

    private fun now(): OffsetDateTime = OffsetDateTime.now(clock).truncatedTo(ChronoUnit.MILLIS)  // DB 는 마이크로초까지라 맞춰 둬요

    @Transactional
    fun start(rawNickname: String): BoothStartResponse {
        val nickname = rawNickname.trim()
        if (nickname.isEmpty() || nickname.length > 10) throw ApiException(ErrorCode.VALIDATION_FAILED, "nickname: 1~10자로 써 주세요.")
        val id = UUID.randomUUID()
        // unique 위반으로 트랜잭션이 깨지지 않게 on conflict 로 확인해요
        val inserted = jdbc.sql("insert into booth_plays (id, nickname, started_at) values (:id, :n, :t) on conflict (nickname) do nothing")
            .param("id", id).param("n", nickname).param("t", now()).update()
        if (inserted == 0) throw ApiException(ErrorCode.NICKNAME_TAKEN)
        return BoothStartResponse(id, items.map { BoothQuestion(it.stem, it.choices) })
    }

    @Transactional
    fun submit(playId: UUID, choices: List<Int>): BoothResult {
        if (choices.size != items.size) throw ApiException(ErrorCode.VALIDATION_FAILED, "choices: ${items.size}개를 보내 주세요.")
        data class Play(val nickname: String, val startedAt: OffsetDateTime, val submitted: Boolean)
        val play = jdbc.sql("select nickname, started_at, submitted_at from booth_plays where id = :id for update")
            .param("id", playId)
            .query { rs, _ -> Play(rs.getString("nickname"), rs.getObject("started_at", OffsetDateTime::class.java), rs.getObject("submitted_at") != null) }
            .optional().orElseThrow { ApiException(ErrorCode.NOT_FOUND) }
        if (play.submitted) throw ApiException(ErrorCode.ALREADY_ATTEMPTED)

        val now = now()
        val elapsedMs = Duration.between(play.startedAt, now).toMillis().coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
        val answers = items.zip(choices) { item, c -> BoothAnswer(item.answer, c, item.answer == c, item.explanation) }
        val correct = answers.count { it.correct }
        jdbc.sql("update booth_plays set submitted_at = :t, choices = :c, correct = :k, elapsed_ms = :e where id = :id")
            .param("t", now).param("c", choices.joinToString(",")).param("k", correct).param("e", elapsedMs).param("id", playId)
            .update()

        val rank = jdbc.sql(
            """
            select count(*) + 1 from booth_plays
             where submitted_at is not null and id <> :id
               and (correct > :k or (correct = :k and elapsed_ms < :e) or (correct = :k and elapsed_ms = :e and submitted_at < :t))
            """.trimIndent(),
        ).param("id", playId).param("k", correct).param("e", elapsedMs).param("t", now).query(Int::class.java).single()
        return BoothResult(play.nickname, correct, items.size, elapsedMs, rank, players(), answers)
    }

    @Transactional(readOnly = true)
    fun ranking(limit: Int): BoothRanking {
        val rows = jdbc.sql(
            """
            select nickname, correct, elapsed_ms from booth_plays
             where submitted_at is not null
             order by correct desc, elapsed_ms, submitted_at
             limit :l
            """.trimIndent(),
        ).param("l", limit).query { rs, i -> BoothRankRow(i + 1, rs.getString("nickname"), rs.getInt("correct"), rs.getInt("elapsed_ms")) }.list()
        return BoothRanking(items.size, players(), rows)
    }

    private fun players(): Int =
        jdbc.sql("select count(*) from booth_plays where submitted_at is not null").query(Int::class.java).single()
}
