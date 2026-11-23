package top.sincs.sinjav.actor

import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.Timestamp
import java.time.Instant

private val ACTRESS_MAPPER = RowMapper { rs, _ ->
    ActressEntity(
        id = rs.getLong("id"),
        name = rs.getString("name"),
        photoPath = rs.getString("photo_path"),
        alias = rs.getString("alias"),
        embyPersonId = rs.getString("emby_person_id"),
        // SQLite 下 updated_at 可能是 INTEGER epoch 或 TEXT（迁移回填的 CURRENT_TIMESTAMP），
        // 驱动按 date_string_format 解析，两者都能读；为空时不报错
        updatedAt = runCatching { rs.getTimestamp("updated_at")?.toInstant() }.getOrNull(),
    )
}

/** 列表行映射：复用 [ACTRESS_MAPPER] 读主数据，另取相关子查询的 `movie_count` */
private val ACTRESS_WITH_COUNT_MAPPER = RowMapper { rs, rowNum ->
    ActressRepository.ActressWithCount(
        actress = ACTRESS_MAPPER.mapRow(rs, rowNum),
        movieCount = rs.getLong("movie_count"),
    )
}

/**
 * 演员主数据仓储（actresses 表）。
 *
 * 只承担演员库自身的读写（照片路径/规范名/Emby Person Id）；影片关联仍由 MovieRepository 维护
 * （女优名按 name 做主数据 upsert，避免两处重复实现）。
 */
@Repository
class ActressRepository(private val jdbc: NamedParameterJdbcTemplate) {

    fun findById(id: Long): ActressEntity? =
        jdbc.query("SELECT * FROM actresses WHERE id = :id", mapOf("id" to id), ACTRESS_MAPPER).firstOrNull()

    fun findByName(name: String): ActressEntity? =
        jdbc.query("SELECT * FROM actresses WHERE name = :name", mapOf("name" to name), ACTRESS_MAPPER).firstOrNull()

    /**
     * 按**规范名**查演员：`COALESCE(NULLIF(alias, ''), name)`，与 [ActressService.canonicalName] 同语义。
     * Emby 同步归一化后拿到的是规范名，而它可能落在 alias 列（name 是另一种写法），故不能只用 [findByName]。
     */
    fun findByCanonicalName(canonical: String): ActressEntity? =
        jdbc.query(
            "SELECT * FROM actresses WHERE COALESCE(NULLIF(alias, ''), name) = :n ORDER BY id LIMIT 1",
            mapOf("n" to canonical), ACTRESS_MAPPER,
        ).firstOrNull()

    /**
     * 新增演员（name 已存在则跳过），返回**新插入演员的 id**（已存在返回 null）。
     * 供 Emby 演员同步/Emby 重刮批量建号：Emby Person Id 与名字一次写入，避免二次 UPDATE；
     * 返回 id 让调用方（刮削收尾）能准确识别「本次新出现的演员」，用于触发头像自动补全。
     */
    fun insertIfAbsent(name: String, embyPersonId: String? = null): Long? {
        val inserted = jdbc.update(
            "INSERT INTO actresses (name, emby_person_id, updated_at) VALUES (:n, :e, :ts) ON CONFLICT (name) DO NOTHING",
            mapOf("n" to name, "e" to embyPersonId, "ts" to Timestamp.from(Instant.now())),
        ) > 0
        if (!inserted) return null
        // name 唯一，插入成功后必能查到；查不到按「未新增」处理（不抛出，避免影响主流程）
        return jdbc.queryForObject("SELECT id FROM actresses WHERE name = :n", mapOf("n" to name), Long::class.java)
    }

    /**
     * 按别名反查规范名：actress_aliases JOIN actresses，取 `actress.alias`（空则回退 `actress.name`）。
     * 未命中返回 null（调用方保持原名）。
     */
    fun findCanonicalNameByAlias(alias: String): String? =
        jdbc.query(
            """
            SELECT COALESCE(NULLIF(a.alias, ''), a.name) AS canonical
            FROM actress_aliases al
            JOIN actresses a ON a.id = al.actress_id
            WHERE al.alias = :alias
            """.trimIndent(),
            mapOf("alias" to alias),
        ) { rs, _ -> rs.getString("canonical") }.firstOrNull()

    /** 尚无本地照片（photo_path 为 NULL 或空串）的演员 id，按 id 升序 */
    fun findIdsMissingPhoto(): List<Long> =
        jdbc.queryForList(
            "SELECT id FROM actresses WHERE photo_path IS NULL OR photo_path = '' ORDER BY id",
            emptyMap<String, Any>(), Long::class.java,
        ).filterNotNull()

    /**
     * 在给定 id 中筛出**尚无本地照片**的演员（自动补全前的过滤：已有头像的不必重复抓取）。
     * 返回顺序按 id 升序；[ids] 为空返回空列表（不产生 IN () 的非法 SQL）。
     */
    fun findIdsMissingPhoto(ids: Collection<Long>): List<Long> {
        if (ids.isEmpty()) return emptyList()
        return jdbc.queryForList(
            "SELECT id FROM actresses WHERE id IN (:ids) AND (photo_path IS NULL OR photo_path = '') ORDER BY id",
            mapOf("ids" to ids.toList()), Long::class.java,
        ).filterNotNull()
    }

    /**
     * 回写本地照片相对路径（如 actors/夢乃あいか.jpg）并刷新 updated_at。
     * updated_at 用 JDBC 参数写（SQLite 存 epoch，与 jobs/movies 等表的写法一致）。
     */
    fun updatePhotoPath(id: Long, photoPath: String) {
        jdbc.update(
            "UPDATE actresses SET photo_path = :p, updated_at = :ts WHERE id = :id",
            mapOf("p" to photoPath, "ts" to Timestamp.from(Instant.now()), "id" to id),
        )
    }

    /** 回写规范名（Gfriends 匹配与文件命名用）与 Emby Person Id（批次 4/5 使用） */
    fun updateAlias(id: Long, alias: String?) {
        jdbc.update(
            "UPDATE actresses SET alias = :a, updated_at = :ts WHERE id = :id",
            mapOf("a" to alias, "ts" to Timestamp.from(Instant.now()), "id" to id),
        )
    }

    fun updateEmbyPersonId(id: Long, embyPersonId: String?) {
        jdbc.update(
            "UPDATE actresses SET emby_person_id = :e, updated_at = :ts WHERE id = :id",
            mapOf("e" to embyPersonId, "ts" to Timestamp.from(Instant.now()), "id" to id),
        )
    }

    // ---------------------------------------------------------------- 演员管理（批次 5）

    /** 列表行：演员主数据 + 关联影片数 */
    data class ActressWithCount(val actress: ActressEntity, val movieCount: Long)

    /** 分页结果：[total] 为过滤后的总行数（分页前），供前端计算总页数 */
    data class Page(val items: List<ActressWithCount>, val total: Long)

    /** 演员所属影片的精简行（movie_actresses JOIN movies），封面路径由 DTO 层按番号拼装 */
    data class ActressMovie(val id: Long, val number: String, val title: String?)

    /** 详情：演员主数据 + 所属影片 + 影片数 */
    data class ActressDetail(val actress: ActressEntity, val movies: List<ActressMovie>, val movieCount: Long)

    /**
     * 分页列表（演员网格）：
     * - [search] 同时匹配 `name` / `alias` / 别名表（`actress_aliases`），空则不过滤；
     * - [hasPhoto] 三态：true = 有头像、false = 无头像（`photo_path` 为 NULL 或空串）、null = 不过滤；
     * - `movie_count` 用相关子查询一次取回，避免逐行 COUNT（N+1）。
     */
    fun findAllPaged(page: Int, size: Int, search: String?, hasPhoto: Boolean?): Page {
        val conditions = mutableListOf<String>()
        val params = mutableMapOf<String, Any>()
        if (!search.isNullOrBlank()) {
            // 双方言兼容：LOWER + LIKE（SQLite 的 LIKE 对 ASCII 本就不区分大小写，PostgreSQL 需显式 LOWER）
            conditions += """
                (LOWER(a.name) LIKE LOWER(:kw) OR LOWER(a.alias) LIKE LOWER(:kw)
                 OR EXISTS (SELECT 1 FROM actress_aliases al
                            WHERE al.actress_id = a.id AND LOWER(al.alias) LIKE LOWER(:kw)))
            """.trimIndent()
            params["kw"] = "%${search.trim()}%"
        }
        when (hasPhoto) {
            true -> conditions += "(a.photo_path IS NOT NULL AND a.photo_path <> '')"
            false -> conditions += "(a.photo_path IS NULL OR a.photo_path = '')"
            null -> Unit
        }
        val where = if (conditions.isEmpty()) "" else "WHERE ${conditions.joinToString(" AND ")}"

        val total = jdbc.queryForObject(
            "SELECT count(*) FROM actresses a $where", params, Long::class.java,
        ) ?: 0L
        val items = jdbc.query(
            """
            SELECT a.*, (SELECT count(*) FROM movie_actresses ma WHERE ma.actress_id = a.id) AS movie_count
            FROM actresses a $where
            ORDER BY a.id DESC
            LIMIT :limit OFFSET :offset
            """.trimIndent(),
            params + mapOf("limit" to size, "offset" to (page - 1).coerceAtLeast(0) * size),
            ACTRESS_WITH_COUNT_MAPPER,
        )
        return Page(items, total)
    }

    /** 详情（演员 + 所属影片，按影片 id 倒序）；演员不存在返回 null */
    fun findByIdWithMovies(id: Long): ActressDetail? {
        val actress = findById(id) ?: return null
        val movies = jdbc.query(
            """
            SELECT m.id, m.number, m.title
            FROM movie_actresses ma
            JOIN movies m ON m.id = ma.movie_id
            WHERE ma.actress_id = :aid
            ORDER BY m.id DESC
            """.trimIndent(),
            mapOf("aid" to id),
        ) { rs, _ ->
            ActressMovie(id = rs.getLong("id"), number = rs.getString("number"), title = rs.getString("title"))
        }
        return ActressDetail(actress, movies, countMovies(id))
    }

    /** 演员关联的影片数（详情页展示；与 [findAllPaged] 的 movie_count 同源） */
    fun countMovies(actressId: Long): Long =
        jdbc.queryForObject(
            "SELECT count(*) FROM movie_actresses WHERE actress_id = :aid",
            mapOf("aid" to actressId), Long::class.java,
        ) ?: 0L
}
