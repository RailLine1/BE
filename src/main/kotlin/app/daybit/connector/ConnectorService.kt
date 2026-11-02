package app.daybit.connector

import app.daybit.global.config.DaybitProperties
import app.daybit.global.security.TokenHasher
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime

/**
 * 개인 커넥터 주소 관리 (P-AI-02, 04).
 * 주소 = {publicBaseUrl}/mcp/u/{token}. 토큰 원문은 저장하지 않고 해시만 저장해요.
 * 그래서 전체 주소는 발급 직후 응답에서만 보여 줄 수 있어요.
 */
@Service
class ConnectorService(
    private val jdbc: JdbcClient,
    private val props: DaybitProperties,
) {
    private val base get() = props.publicBaseUrl.trimEnd('/') + "/mcp/u/"

    /** 아직 주소가 없으면 처음 발급하고 전체 주소를 보여 줘요. 있으면 가린 주소 */
    @Transactional
    fun get(userId: Long): ConnectorResponse {
        val exists = jdbc.sql("select count(*) from connector_tokens where user_id = :u").param("u", userId)
            .query(Int::class.java).single() > 0
        if (!exists) return reissue(userId)
        return ConnectorResponse(base + MASK, clients(userId), fullUrlShown = false)
    }

    /** 새 주소를 만들고 이전 주소는 바로 무효로 만들어요 */
    @Transactional
    fun reissue(userId: Long): ConnectorResponse {
        val token = TokenHasher.newToken()
        jdbc.sql(
            """
            insert into connector_tokens (user_id, token_hash, issued_at) values (:u, :h, now())
            on conflict (user_id) do update set token_hash = :h, issued_at = now()
            """.trimIndent(),
        ).param("u", userId).param("h", TokenHasher.sha256(token)).update()
        return ConnectorResponse(base + token, clients(userId), fullUrlShown = true)
    }

    /** 주소의 토큰 → 사용자. 없거나 재발급으로 무효면 null */
    fun userOf(token: String): Long? =
        jdbc.sql("select user_id from connector_tokens where token_hash = :h").param("h", TokenHasher.sha256(token))
            .query(Long::class.java).optional().orElse(null)

    /** P-AI-04: 호출 기록. 클라이언트 종류별 최근 호출로 등록 상태를 보여 줘요 */
    fun record(userId: Long, client: ConnectorClientType, tool: String, courseId: Long?) {
        jdbc.sql("insert into connector_calls (user_id, client, tool, course_id) values (:u, :c, :t, :course)")
            .param("u", userId).param("c", client.name).param("t", tool.take(30)).param("course", courseId).update()
    }

    private fun clients(userId: Long): List<ConnectorClientStatus> {
        val last = jdbc.sql("select client, max(called_at) as last from connector_calls where user_id = :u group by client")
            .param("u", userId)
            .query { rs, _ -> rs.getString("client") to rs.getObject("last", OffsetDateTime::class.java) }
            .list().toMap()
        return ConnectorClient.entries.map { ConnectorClientStatus(it, last[it.name]) }
    }

    companion object {
        private const val MASK = "••••••••"
    }
}

/** connector_calls.client 값. ConnectorClient(응답용 4종)에 unknown 을 더했어요 */
@Suppress("EnumEntryName")
enum class ConnectorClientType { claude, chatgpt, claudecode, codex, unknown }
