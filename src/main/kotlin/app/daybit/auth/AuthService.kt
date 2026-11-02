package app.daybit.auth

import app.daybit.global.error.ApiException
import app.daybit.global.error.ErrorCode
import app.daybit.user.User
import app.daybit.user.UserRepository
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class AuthService(
    private val users: UserRepository,
    private val passwordEncoder: PasswordEncoder,
    private val tokens: TokenService,
) {

    /** P-ACC-02~04: 아이디 가입은 닉네임을 함께 받아요 */
    @Transactional
    fun signup(req: SignupRequest): AuthResponse {
        if (users.existsByLoginId(req.loginId)) throw ApiException(ErrorCode.LOGIN_ID_TAKEN)
        if (users.existsByNickname(req.nickname)) throw ApiException(ErrorCode.NICKNAME_TAKEN)
        val user = try {
            users.saveAndFlush(
                User(
                    loginId = req.loginId,
                    passwordHash = passwordEncoder.encode(req.password),
                    nickname = req.nickname,
                ),
            )
        } catch (e: DataIntegrityViolationException) {
            // 동시에 같은 아이디·닉네임으로 가입한 경우
            throw ApiException(ErrorCode.LOGIN_ID_TAKEN)
        }
        return response(user)
    }

    @Transactional
    fun login(req: LoginRequest): AuthResponse {
        val user = users.findByLoginId(req.loginId)
            ?.takeIf { it.passwordHash != null && passwordEncoder.matches(req.password, it.passwordHash) }
            ?: throw ApiException(ErrorCode.INVALID_CREDENTIALS)
        return response(user)
    }

    @Transactional
    fun refresh(req: RefreshRequest): AuthResponse {
        val (userId, pair) = tokens.rotate(req.refreshToken)
        val user = users.findById(userId).orElseThrow { ApiException(ErrorCode.INVALID_REFRESH_TOKEN) }
        return AuthResponse(AuthUser(user.id, user.nickname), isFirstLogin = user.onboardedAt == null, tokens = pair)
    }

    @Transactional
    fun logout(req: RefreshRequest) = tokens.revoke(req.refreshToken)

    private fun response(user: User) = AuthResponse(
        user = AuthUser(user.id, user.nickname),
        isFirstLogin = user.onboardedAt == null,
        tokens = tokens.issue(user.id),
    )
}
