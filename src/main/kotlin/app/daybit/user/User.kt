package app.daybit.user

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.OffsetDateTime

@Entity
@Table(name = "users")
class User(
    @Column(name = "login_id", length = 20, unique = true)
    var loginId: String? = null,

    @Column(name = "password_hash")
    var passwordHash: String? = null,

    @Column(name = "github_id", unique = true)
    var githubId: Long? = null,

    @Column(name = "github_login", length = 39)
    var githubLogin: String? = null,

    @Column(nullable = false, length = 12, unique = true)
    var nickname: String,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0

    @Column(name = "onboarded_at")
    var onboardedAt: OffsetDateTime? = null

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: OffsetDateTime = OffsetDateTime.now()

    val githubLinked: Boolean get() = githubId != null
}
