package app.daybit.auth

import app.daybit.global.config.DaybitProperties
import app.daybit.global.error.ApiException
import app.daybit.global.error.ErrorCode
import app.daybit.global.security.TokenHasher
import app.daybit.user.User
import app.daybit.user.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.util.UriComponentsBuilder
import tools.jackson.databind.json.JsonMapper
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random

/**
 * GitHub 로그인 · 계정 연결 (결정 D3, P-ACC-04, 06).
 * 1) start: 앱 주소(redirect)를 서명한 state 에 담아 GitHub 로 보내요.
 * 2) callback: GitHub 사용자를 찾거나 만들고, 일회용 code 를 붙여 앱 주소로 돌려보내요.
 * 3) exchange: 앱이 code 를 토큰으로 바꿔요. code 는 1분 안에 한 번만 쓸 수 있어요.
 * 실패하면 앱 주소로 `?error=코드` 를 붙여 돌려보내요.
 */
/** 작업 공간(#10) 1단계: GitHub 콜백에서 저장소를 만들어요. 실패하면 앱으로 돌려보낼 error 값 */
fun interface WorkspaceLinker {
    fun link(userId: Long, code: String, redirectUri: String): String?
}

@Service
class GithubAuthService(
    private val props: DaybitProperties,
    private val github: GithubClient,
    private val users: UserRepository,
    private val tokens: TokenService,
    private val jdbc: JdbcClient,
    private val json: JsonMapper,
    private val workspace: ObjectProvider<WorkspaceLinker>,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val key = SecretKeySpec(props.jwt.secret.toByteArray(), "HmacSHA256")

    private val callbackUrl get() = props.publicBaseUrl.trimEnd('/') + "/api/v1/auth/github/callback"

    /**
     * GitHub 인가 페이지 주소. linkUserId 가 있으면 로그인 대신 그 계정에 연결해요.
     * workspace = true 면 작업 공간 1단계(#10): 저장소 · 워크플로 권한을 요청하고 콜백에서 저장소를 만들어요.
     */
    fun authorizeUrl(redirect: String, linkUserId: Long? = null, workspace: Boolean = false): String {
        if (!isAllowedRedirect(redirect)) throw ApiException(ErrorCode.VALIDATION_FAILED, "redirect: 허용되지 않은 주소예요.")
        val purpose = if (workspace) PURPOSE_WORKSPACE else PURPOSE_LOGIN
        val state = sign(State(redirect, linkUserId, Instant.now().plus(STATE_TTL).epochSecond, TokenHasher.newToken(8), purpose))
        return UriComponentsBuilder.fromUriString("https://github.com/login/oauth/authorize")
            .queryParam("client_id", props.github.clientId)
            .queryParam("redirect_uri", callbackUrl)
            .queryParam("scope", if (workspace) "read:user repo workflow" else "read:user")
            .queryParam("state", state)
            .encode().build().toUriString()
    }

    /** 앱으로 돌아갈 주소(일회용 code 또는 error 포함)를 돌려줘요 */
    @Transactional
    fun callback(code: String?, rawState: String, githubError: String?): String {
        val state = verify(rawState) ?: throw ApiException(ErrorCode.VALIDATION_FAILED, "state: 로그인을 처음부터 다시 시도해 주세요.")
        if (githubError != null || code.isNullOrBlank()) return redirectWith(state.redirect, "error", "GITHUB_CANCELLED")
        if (state.purpose == PURPOSE_WORKSPACE && state.linkUserId != null) {
            val error = workspace.getObject().link(state.linkUserId, code, callbackUrl)
            return if (error == null) redirectWith(state.redirect, "workspace", "linked") else redirectWith(state.redirect, "error", error)
        }

        val ghUser = try {
            github.fetchUser(code, callbackUrl)
        } catch (e: Exception) {
            log.warn("GitHub 사용자 조회 실패", e)
            return redirectWith(state.redirect, "error", "GITHUB_FAILED")
        }

        val owner = users.findByGithubId(ghUser.id)
        val (user, isNew) = if (state.linkUserId != null) {
            // P-ACC-06: 로그인한 계정에 연결
            if (owner != null && owner.id != state.linkUserId) {
                return redirectWith(state.redirect, "error", ErrorCode.GITHUB_ALREADY_LINKED.name)
            }
            val me = users.findById(state.linkUserId).orElse(null)
                ?: return redirectWith(state.redirect, "error", ErrorCode.UNAUTHORIZED.name)
            me.githubId = ghUser.id
            me.githubLogin = ghUser.login
            me to false
        } else if (owner != null) {
            owner.githubLogin = ghUser.login
            owner to false
        } else {
            // P-ACC-04: 새 사용자는 임시 닉네임으로 만들고, 앱이 닉네임 입력을 거쳐 바꿔요
            users.saveAndFlush(User(githubId = ghUser.id, githubLogin = ghUser.login, nickname = tempNickname())) to true
        }

        val oneTime = TokenHasher.newToken()
        jdbc.sql(
            """
            insert into github_login_codes (code_hash, user_id, needs_nickname, expires_at)
            values (:hash, :u, :needs, :exp)
            """.trimIndent(),
        ).param("hash", TokenHasher.sha256(oneTime)).param("u", user.id).param("needs", isNew)
            .param("exp", OffsetDateTime.now().plus(CODE_TTL)).update()
        return redirectWith(state.redirect, "code", oneTime)
    }

    /** 일회용 code → 토큰. 없거나 만료 · 사용된 code 는 401 GITHUB_CODE_INVALID */
    @Transactional
    fun exchange(code: String): GithubAuthResponse {
        val row = jdbc.sql(
            """
            update github_login_codes set used_at = now()
             where code_hash = :hash and used_at is null and expires_at > now()
            returning user_id, needs_nickname
            """.trimIndent(),
        ).param("hash", TokenHasher.sha256(code.trim()))
            .query { rs, _ -> rs.getLong("user_id") to rs.getBoolean("needs_nickname") }
            .optional().orElseThrow { ApiException(ErrorCode.GITHUB_CODE_INVALID) }
        val (userId, needsNickname) = row
        val user = users.findById(userId).orElseThrow { ApiException(ErrorCode.GITHUB_CODE_INVALID) }
        return GithubAuthResponse(
            user = AuthUser(user.id, user.nickname),
            isFirstLogin = user.onboardedAt == null,
            needsNickname = needsNickname,
            githubLogin = user.githubLogin,
            tokens = tokens.issue(user.id),
        )
    }

    // ---------- 공통 ----------

    internal fun isAllowedRedirect(redirect: String): Boolean =
        props.github.allowedRedirects.map { it.trim() }.filter { it.isNotEmpty() }.any { pattern ->
            Regex(pattern.split("*").joinToString(".*") { Regex.escape(it) }).matches(redirect)
        }

    private fun redirectWith(redirect: String, name: String, value: String): String =
        UriComponentsBuilder.fromUriString(redirect).queryParam(name, value).encode().build().toUriString()

    /** 겹치지 않는 임시 닉네임 (P-ACC-03 형식: 영문 · 숫자 2~12자) */
    private fun tempNickname(): String =
        generateSequence { "user" + Random.nextInt(10_000_000, 99_999_999) }
            .first { !users.existsByNickname(it) }

    private data class State(val redirect: String, val linkUserId: Long?, val exp: Long, val nonce: String, val purpose: String = PURPOSE_LOGIN)

    private fun sign(state: State): String {
        val payload = Base64.getUrlEncoder().withoutPadding().encodeToString(json.writeValueAsBytes(state))
        return "$payload.${mac(payload)}"
    }

    private fun verify(raw: String): State? {
        val (payload, sig) = raw.split('.').takeIf { it.size == 2 } ?: return null
        if (!MessageDigest.isEqual(mac(payload).toByteArray(), sig.toByteArray())) return null
        val state = runCatching { json.readValue(Base64.getUrlDecoder().decode(payload), State::class.java) }.getOrNull() ?: return null
        return state.takeIf { Instant.now().epochSecond <= it.exp }
    }

    private fun mac(payload: String): String {
        val mac = Mac.getInstance("HmacSHA256").apply { init(key) }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(payload.toByteArray()))
    }

    companion object {
        private val STATE_TTL: Duration = Duration.ofMinutes(10)
        private val CODE_TTL: Duration = Duration.ofMinutes(1)
        private const val PURPOSE_LOGIN = "login"
        private const val PURPOSE_WORKSPACE = "workspace"
    }
}
