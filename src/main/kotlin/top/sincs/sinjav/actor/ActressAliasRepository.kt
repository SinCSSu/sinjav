package top.sincs.sinjav.actor

import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional

private val ACTRESS_ALIAS_MAPPER = RowMapper { rs, _ ->
    ActressAliasEntity(
        id = rs.getLong("id"),
        actressId = rs.getLong("actress_id"),
        alias = rs.getString("alias"),
    )
}

/** 演员别名仓储（actress_aliases 表） */
@Repository
class ActressAliasRepository(private val jdbc: NamedParameterJdbcTemplate) {

    fun findByActressId(actressId: Long): List<ActressAliasEntity> =
        jdbc.query(
            "SELECT * FROM actress_aliases WHERE actress_id = :aid ORDER BY id",
            mapOf("aid" to actressId),
            ACTRESS_ALIAS_MAPPER,
        )

    /** 按别名反查（编辑时判断别名是否已被其他演员占用） */
    fun findByAlias(alias: String): ActressAliasEntity? =
        jdbc.query(
            "SELECT * FROM actress_aliases WHERE alias = :alias",
            mapOf("alias" to alias),
            ACTRESS_ALIAS_MAPPER,
        ).firstOrNull()

    /** 别名列表（Gfriends 匹配按录入顺序逐一尝试） */
    fun findAliases(actressId: Long): List<String> = findByActressId(actressId).map { it.alias }

    /**
     * 全量替换某演员的别名（详情页编辑语义）。
     * alias 列带 UNIQUE 约束，撞其他演员已占用的别名时该行被跳过，是否提示 409 由调用方决定。
     */
    @Transactional
    fun replaceForActress(actressId: Long, aliases: Collection<String>) {
        jdbc.update("DELETE FROM actress_aliases WHERE actress_id = :aid", mapOf("aid" to actressId))
        val cleaned = aliases.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (cleaned.isEmpty()) return
        jdbc.batchUpdate(
            "INSERT INTO actress_aliases (actress_id, alias) VALUES (:aid, :alias) ON CONFLICT (alias) DO NOTHING",
            cleaned.map { mapOf<String, Any?>("aid" to actressId, "alias" to it) }.toTypedArray(),
        )
    }
}
