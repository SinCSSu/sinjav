package top.sincs.sinjav.actor

import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional

/** 批量 upsert 的分片大小（Filetree.json 可达数万条，避免单次参数过多） */
private const val GFRIENDS_UPSERT_CHUNK = 500

private val GFRIENDS_INDEX_MAPPER = RowMapper { rs, _ ->
    GfriendsIndexEntity(name = rs.getString("name"), url = rs.getString("url"))
}

/** Gfriends 头像库索引仓储（gfriends_index 表） */
@Repository
class GfriendsIndexRepository(private val jdbc: NamedParameterJdbcTemplate) {

    fun findByName(name: String): GfriendsIndexEntity? =
        jdbc.query(
            "SELECT name, url FROM gfriends_index WHERE name = :name",
            mapOf("name" to name),
            GFRIENDS_INDEX_MAPPER,
        ).firstOrNull()

    /** 批量按名查 URL（演员名 + 别名一次查齐，避免逐条往返） */
    fun findUrlsByNames(names: Collection<String>): Map<String, String> {
        if (names.isEmpty()) return emptyMap()
        return jdbc.query(
            "SELECT name, url FROM gfriends_index WHERE name IN (:names)",
            mapOf("names" to names.distinct()),
            GFRIENDS_INDEX_MAPPER,
        ).associate { it.name to it.url }
    }

    fun count(): Long =
        jdbc.queryForObject("SELECT count(*) FROM gfriends_index", emptyMap<String, Any>(), Long::class.java) ?: 0L

    /** 索引同步：按 name upsert，同名覆盖 url（双方言均支持 excluded 伪表） */
    @Transactional
    fun upsertAll(entries: Collection<GfriendsIndexEntity>) {
        entries.chunked(GFRIENDS_UPSERT_CHUNK).forEach { chunk ->
            jdbc.batchUpdate(
                """
                INSERT INTO gfriends_index (name, url) VALUES (:name, :url)
                ON CONFLICT (name) DO UPDATE SET url = excluded.url
                """,
                chunk.map { mapOf<String, Any?>("name" to it.name, "url" to it.url) }.toTypedArray(),
            )
        }
    }
}
