package top.sincs.sinjav.domain

import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.sql.Timestamp
import java.time.Instant

/**
 * Emby 媒体库同步快照（emby_items 表）：
 * 「同步 Emby 库」时全量替换，查询侧用番号判断某影片是否已存在于 Emby。
 */
data class EmbyItemRecord(
    val itemId: String,
    val number: String,
    val name: String?,
    val path: String?,
    val syncedAt: Instant,
)

private val EMBY_ITEM_MAPPER = RowMapper { rs, _ ->
    EmbyItemRecord(
        itemId = rs.getString("item_id"),
        number = rs.getString("number"),
        name = rs.getString("name"),
        path = rs.getString("path"),
        syncedAt = rs.getTimestamp("synced_at").toInstant(),
    )
}

@Repository
class EmbyItemRepository(private val jdbc: NamedParameterJdbcTemplate) {

    /** 全量替换同步结果（删除旧快照后批量插入） */
    @Transactional
    fun replaceAll(items: List<EmbyItemRecord>) {
        jdbc.update("DELETE FROM emby_items", emptyMap<String, Any>())
        val now = Timestamp.from(Instant.now())
        jdbc.batchUpdate(
            """
            INSERT INTO emby_items (item_id, number, name, path, synced_at)
            VALUES (:itemId, :number, :name, :path, :syncedAt)
            """,
            items.map {
                mapOf<String, Any?>(
                    "itemId" to it.itemId, "number" to it.number,
                    "name" to it.name, "path" to it.path, "syncedAt" to now,
                )
            }.toTypedArray(),
        )
    }

    fun existsByNumber(number: String): Boolean =
        findExistingNumbers(listOf(number)).isNotEmpty()

    /** 批量判断番号是否已存在于 Emby（返回存在的番号集合） */
    fun findExistingNumbers(numbers: Collection<String>): Set<String> {
        if (numbers.isEmpty()) return emptySet()
        return jdbc.queryForList(
            "SELECT DISTINCT number FROM emby_items WHERE number IN (:numbers)",
            mapOf("numbers" to numbers.toList()), String::class.java,
        ).filterNotNull().toSet()
    }

    fun count(): Long =
        jdbc.queryForObject("SELECT count(*) FROM emby_items", emptyMap<String, Any>(), Long::class.java) ?: 0L
}
