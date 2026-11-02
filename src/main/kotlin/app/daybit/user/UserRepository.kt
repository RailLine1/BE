package app.daybit.user

import org.springframework.data.jpa.repository.JpaRepository

interface UserRepository : JpaRepository<User, Long> {
    fun findByLoginId(loginId: String): User?
    fun findByGithubId(githubId: Long): User?
    fun existsByLoginId(loginId: String): Boolean
    fun existsByNickname(nickname: String): Boolean
}
