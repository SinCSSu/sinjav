package top.sincs.sinjav.config

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import top.sincs.sinjav.common.AuthException
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 单用户凭据存储与防爆破：
 * - 凭据存 settings 表（key=authUsername / authPasswordHash，BCrypt 哈希），不进通用 Settings API，避免哈希经 /settings 泄露；
 * - 防爆破：全局连续失败计数，连续 5 次失败锁定 15 分钟（内存实现，重启清零）。
 */
@Service
class AuthService(
    private val jdbc: NamedParameterJdbcTemplate,
    private val encoder: PasswordEncoder,
) {

    data class Credentials(val username: String, val passwordHash: String)

    companion object {
        private const val KEY_USERNAME = "authUsername"
        private const val KEY_PASSWORD_HASH = "authPasswordHash"
        private const val MAX_FAILURES = 5
        private val LOCK_DURATION: Duration = Duration.ofMinutes(15)
    }

    private val failCount = AtomicInteger()
    private val lockedUntil = AtomicLong(0)

    fun load(): Credentials? {
        val rows = jdbc.query(
            "SELECT key, value FROM settings WHERE key IN (:k1, :k2)",
            mapOf("k1" to KEY_USERNAME, "k2" to KEY_PASSWORD_HASH),
        ) { rs, _ -> rs.getString("key") to rs.getString("value") }
            .toMap()
        val u = rows[KEY_USERNAME] ?: return null
        val h = rows[KEY_PASSWORD_HASH] ?: return null
        return Credentials(u, h)
    }

    fun initialized(): Boolean = load() != null

    /** 初始化凭据（仅库中无凭据时可用，先到先得；已存在返回 409） */
    fun init(username: String, password: String) {
        if (initialized()) throw AuthException(409, "账号已初始化，请直接登录")
        save(username, password)
    }

    /** 修改凭据：验证当前密码；用户名/新密码至少改一项（由控制器校验） */
    fun updateCredentials(currentPassword: String, username: String?, newPassword: String?) {
        val cred = load() ?: throw AuthException(409, "账号尚未初始化")
        if (!encoder.matches(currentPassword, cred.passwordHash)) {
            throw IllegalArgumentException("当前密码不正确")
        }
        save(username ?: cred.username, newPassword ?: return)
    }

    fun verify(username: String, password: String): Boolean {
        val cred = load() ?: return false
        return cred.username == username && encoder.matches(password, cred.passwordHash)
    }

    /** 防爆破：锁定期间抛 429（含剩余分钟数），登录成功后由调用方 resetFailures */
    fun checkLock() {
        val until = lockedUntil.get()
        if (until > 0 && Instant.now().toEpochMilli() < until) {
            val minutes = TimeUnit.MILLISECONDS.toMinutes(until - Instant.now().toEpochMilli()) + 1
            throw AuthException(429, "失败次数过多，账号已临时锁定，请约 ${minutes} 分钟后再试")
        }
    }

    fun recordFailure() {
        if (failCount.incrementAndGet() >= MAX_FAILURES) {
            lockedUntil.set(Instant.now().plus(LOCK_DURATION).toEpochMilli())
            failCount.set(0)
        }
    }

    fun resetFailures() {
        failCount.set(0)
        lockedUntil.set(0)
    }

    private fun save(username: String, password: String) {
        upsert(KEY_USERNAME, username.trim())
        // Spring Security 7 的 PasswordEncoder.encode 返回类型标注可空（BCrypt 实际不会返回 null）
        val hash = encoder.encode(password) ?: throw IllegalStateException("BCrypt 编码失败")
        upsert(KEY_PASSWORD_HASH, hash)
    }

    private fun upsert(key: String, value: String) {
        jdbc.update(
            """
            INSERT INTO settings (key, value) VALUES (:k, :v)
            ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value, updated_at = CURRENT_TIMESTAMP
            """,
            mapOf("k" to key, "v" to value),
        )
    }
}
