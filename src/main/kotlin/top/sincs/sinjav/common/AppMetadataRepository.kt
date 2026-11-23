package top.sincs.sinjav.common

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository

/**
 * 通用应用元数据仓储（app_metadata 表，V7 迁移）。
 *
 * 存程序自己维护的运行期状态（如 Gfriends 索引最后同步时间），
 * 与 settings 表（用户可改的配置覆盖值）分工明确，避免把状态混进用户配置。
 */
@Repository
class AppMetadataRepository(private val jdbc: NamedParameterJdbcTemplate) {

    fun get(key: String): String? =
        jdbc.query(
            "SELECT value FROM app_metadata WHERE key = :k",
            mapOf("k" to key),
        ) { rs, _ -> rs.getString("value") }.firstOrNull()

    /** 数值型元数据（不存在或非数字返回 null） */
    fun getLong(key: String): Long? = get(key)?.trim()?.toLongOrNull()

    /** 写入/覆盖元数据（双方言均走 ON CONFLICT upsert） */
    fun put(key: String, value: String) {
        jdbc.update(
            """
            INSERT INTO app_metadata (key, value) VALUES (:k, :v)
            ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value, updated_at = CURRENT_TIMESTAMP
            """.trimIndent(),
            mapOf("k" to key, "v" to value),
        )
    }

    fun putLong(key: String, value: Long) = put(key, value.toString())
}
