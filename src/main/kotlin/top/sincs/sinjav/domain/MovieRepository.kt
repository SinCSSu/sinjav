package top.sincs.sinjav.domain

import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.Date
import java.time.LocalDate

private val MOVIE_MAPPER = RowMapper { rs, _ ->
    Movie(
        id = rs.getLong("id"),
        number = rs.getString("number"),
        rawNumber = rs.getString("raw_number"),
        title = rs.getString("title"),
        titleZh = rs.getString("title_zh"),
        titleOrig = rs.getString("title_orig"),
        releaseDate = rs.getDate("release_date")?.toLocalDate(),
        director = rs.getString("director"),
        maker = rs.getString("maker"),
        label = rs.getString("label"),
        series = rs.getString("series"),
        durationMin = rs.getObject("duration_min")?.let { (it as Int) },
        coverUrl = rs.getString("cover_url"),
        previewUrls = rs.getString("preview_urls")?.let { parseJsonArray(it) } ?: emptyList(),
        coverSource = rs.getString("cover_source"),
        previewSource = rs.getString("preview_source"),
        coverCandidates = rs.getString("cover_candidates")?.let { parseCandidates(it) } ?: emptyList(),
        previewCandidates = rs.getString("preview_candidates")?.let { parseCandidates(it) } ?: emptyList(),
        plot = rs.getString("plot"),
        source = rs.getString("source"),
        status = rs.getString("status"),
        failReason = rs.getString("fail_reason"),
        scrapedAt = rs.getTimestamp("scraped_at")?.toInstant(),
        createdAt = rs.getTimestamp("created_at").toInstant(),
        updatedAt = rs.getTimestamp("updated_at").toInstant(),
    )
}

private fun parseJsonArray(json: String): List<String> =
    Regex("\"([^\"]*)\"").findAll(json).map { it.groupValues[1] }.filter { it.isNotBlank() }.toList()

/** 图片候选 JSON 数组（[{"url":"...","source":"..."}]）解析，cover_candidates / preview_candidates 共用；解析失败返回空列表（不阻断查询） */
private fun parseCandidates(json: String): List<top.sincs.sinjav.scraper.CoverCandidate> =
    runCatching {
        mapper.readTree(json).takeIf { it.isArray }?.mapNotNull { node ->
            node.get("url")?.asString()?.takeIf { it.isNotBlank() }
                ?.let { top.sincs.sinjav.scraper.CoverCandidate(it, node.get("source")?.asString()) }
        } ?: emptyList()
    }.getOrDefault(emptyList())

private val mapper = tools.jackson.databind.json.JsonMapper.builder().build()

/** 图片候选序列化为 JSON 数组（与 preview_urls 一致走手写转义，避免引入完整 Jackson 树写入路径） */
private fun serializeCandidates(candidates: List<top.sincs.sinjav.scraper.CoverCandidate>): String =
    candidates.joinToString(",", "[", "]") { c ->
        """{"url":"${c.url.replace("\"", "\\\"")}","source":""" +
            (c.source?.let { "\"${it.replace("\"", "\\\"")}\"" } ?: "null") + "}"
    }

@Repository
class MovieRepository(private val jdbc: NamedParameterJdbcTemplate) {

    fun findByNumber(number: String): Movie? =
        jdbc.query("SELECT * FROM movies WHERE number = :n", mapOf("n" to number), MOVIE_MAPPER).firstOrNull()

    fun findActresses(movieId: Long): List<String> =
        jdbc.queryForList(
            "SELECT a.name FROM movie_actresses ma JOIN actresses a ON a.id = ma.actress_id WHERE ma.movie_id = :id ORDER BY a.id",
            mapOf("id" to movieId), String::class.java,
        ).filterNotNull()

    fun findGenres(movieId: Long): List<String> =
        jdbc.queryForList(
            "SELECT g.name FROM movie_genres mg JOIN genres g ON g.id = mg.genre_id WHERE mg.movie_id = :id ORDER BY g.id",
            mapOf("id" to movieId), String::class.java,
        ).filterNotNull()

    /** 创建或获取待刮削记录（pending）；已存在则返回既有 */
    fun ensurePending(number: String, rawNumber: String?): Movie {
        findByNumber(number)?.let { return it }
        jdbc.update(
            """
            INSERT INTO movies (number, raw_number, status) VALUES (:n, :rn, 'pending')
            ON CONFLICT (number) DO NOTHING
            """,
            mapOf("n" to number, "rn" to rawNumber),
        )
        return findByNumber(number)!!
    }

    /**
     * 落库刮削结果（全字段覆盖）。
     *
     * @return 本次**新建**的演员 id（已存在的不算），供刮削收尾的「新演员头像自动补全」使用
     *   （批次 6 入口 1：在循环内收集，比前后快照 diff 更省一次全表查询且不遗漏）
     */
    fun markScraped(
        number: String,
        title: String?, titleZh: String?, titleOrig: String?, releaseDate: LocalDate?, director: String?,
        maker: String?, label: String?, series: String?, durationMin: Int?,
        coverUrl: String?, previewUrls: List<String>, plot: String?,
        source: String, actresses: List<String>, genres: List<String>,
        coverSource: String? = null, previewSource: String? = null,
        coverCandidates: List<top.sincs.sinjav.scraper.CoverCandidate> = emptyList(),
        previewCandidates: List<top.sincs.sinjav.scraper.CoverCandidate> = emptyList(),
    ): List<Long> {
        val params = mapOf(
            "n" to number, "t" to title, "tz" to titleZh, "to" to titleOrig,
            "rd" to releaseDate?.let { Date.valueOf(it) }, "d" to director, "mk" to maker,
            "lb" to label, "se" to series, "du" to durationMin, "cv" to coverUrl,
            "pv" to previewUrls.joinToString(",", "[", "]") { "\"${it.replace("\"", "\\\"")}\"" },
            "pl" to plot, "src" to source,
            "cs" to coverSource, "ps" to previewSource,
            "cc" to serializeCandidates(coverCandidates.filter { it.url.isNotBlank() }),
            "pc" to serializeCandidates(previewCandidates.filter { it.url.isNotBlank() }),
        )
        jdbc.update(
            """
            UPDATE movies SET
                title = :t, title_zh = :tz, title_orig = :to, release_date = :rd, director = :d, maker = :mk,
                label = :lb, series = :se, duration_min = :du, cover_url = :cv, preview_urls = :pv,
                cover_source = :cs, preview_source = :ps, cover_candidates = :cc, preview_candidates = :pc,
                plot = :pl, source = :src, status = 'scraped', fail_reason = NULL,
                scraped_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
            WHERE number = :n
            """, params,
        )
        val movieId = findByNumber(number)?.id ?: return emptyList()
        // 全字段覆盖语义：先清除旧关联再插入，避免重刮/多源合并时新旧标签（原语言 + 简体）并存
        jdbc.update("DELETE FROM movie_actresses WHERE movie_id = :id", mapOf("id" to movieId))
        jdbc.update("DELETE FROM movie_genres WHERE movie_id = :id", mapOf("id" to movieId))
        val newActressIds = LinkedHashSet<Long>()
        actresses.forEach { name -> linkActress(movieId, name)?.let { newActressIds += it } }
        genres.forEach { name -> linkGenre(movieId, name) }
        return newActressIds.toList()
    }

    fun markFailed(number: String, reason: String) {
        jdbc.update(
            "UPDATE movies SET status = 'failed', fail_reason = :r, updated_at = CURRENT_TIMESTAMP WHERE number = :n",
            mapOf("n" to number, "r" to reason),
        )
    }

    /** 回写封面实际生效来源（下载沿候选列表成功后调用；多候选回退时可能与落库时的首位来源不同） */
    fun markCoverSource(number: String, source: String?) {
        jdbc.update(
            "UPDATE movies SET cover_source = :s, updated_at = CURRENT_TIMESTAMP WHERE number = :n",
            mapOf("n" to number, "s" to source),
        )
    }

    /**
     * 重命名番号（多源对比界面改番号后落库）：movies.number 与 file_records.number 同步更新。
     * 调用方需保证：原番号记录存在、新番号在库中不存在；图片目录沿用新番号由 downloadAll 重建。
     */
    fun rename(oldNumber: String, newNumber: String) {
        jdbc.update(
            "UPDATE movies SET number = :nn, updated_at = CURRENT_TIMESTAMP WHERE number = :on",
            mapOf("nn" to newNumber, "on" to oldNumber),
        )
        jdbc.update(
            "UPDATE file_records SET number = :nn WHERE number = :on",
            mapOf("nn" to newNumber, "on" to oldNumber),
        )
    }

    /** 删除影片记录及其关联（女优/类型关联表），不删除 actress/genre 主数据（可能被其他影片共用）。返回是否存在并删除 */
    fun delete(number: String): Boolean {
        val movie = findByNumber(number) ?: return false
        jdbc.update("DELETE FROM movie_actresses WHERE movie_id = :id", mapOf("id" to movie.id))
        jdbc.update("DELETE FROM movie_genres WHERE movie_id = :id", mapOf("id" to movie.id))
        jdbc.update("DELETE FROM movies WHERE id = :id", mapOf("id" to movie.id))
        return true
    }

    /**
     * 建立影片-女优关联；actresses.name 不存在时顺带建号。
     *
     * @return 本次**新建**演员的 id；演员已存在（或名称为空导致查不到）返回 null
     */
    private fun linkActress(movieId: Long, name: String): Long? {
        val inserted = jdbc.update(
            "INSERT INTO actresses (name) VALUES (:nm) ON CONFLICT (name) DO NOTHING", mapOf("nm" to name),
        ) > 0
        val actressId = jdbc.queryForObject(
            "SELECT id FROM actresses WHERE name = :nm", mapOf("nm" to name), Long::class.java,
        ) ?: return null
        jdbc.update(
            """
            INSERT INTO movie_actresses (movie_id, actress_id)
            SELECT :mid, id FROM actresses WHERE name = :nm
            ON CONFLICT DO NOTHING
            """,
            mapOf("mid" to movieId, "nm" to name),
        )
        return if (inserted) actressId else null
    }

    private fun linkGenre(movieId: Long, name: String) {
        jdbc.update(
            "INSERT INTO genres (name) VALUES (:nm) ON CONFLICT (name) DO NOTHING", mapOf("nm" to name),
        )
        jdbc.update(
            """
            INSERT INTO movie_genres (movie_id, genre_id)
            SELECT :mid, id FROM genres WHERE name = :nm
            ON CONFLICT DO NOTHING
            """,
            mapOf("mid" to movieId, "nm" to name),
        )
    }

    data class Page(val items: List<Movie>, val actressesByMovie: Map<Long, List<String>>, val total: Long)

    fun list(
        page: Int, size: Int, keyword: String?, status: String?, sort: String, order: String,
    ): Page {
        val conditions = mutableListOf<String>()
        val params = mutableMapOf<String, Any>()
        if (!keyword.isNullOrBlank()) {
            // 双方言兼容：LOWER + LIKE（SQLite LIKE 对 ASCII 本就不区分大小写）
            conditions += "(LOWER(number) LIKE LOWER(:kw) OR LOWER(title) LIKE LOWER(:kw))"
            params["kw"] = "%$keyword%"
        }
        if (!status.isNullOrBlank()) { conditions += "status = :st"; params["st"] = status }
        val where = if (conditions.isEmpty()) "" else "WHERE ${conditions.joinToString(" AND ")}"
        val sortCol = when (sort) {
            "release_date" -> "release_date"; "number" -> "number"; else -> "created_at"
        }
        val ord = if (order == "asc") "ASC" else "DESC"

        val total = jdbc.queryForObject("SELECT count(*) FROM movies $where", params, Long::class.java) ?: 0L
        val items = jdbc.query(
            "SELECT * FROM movies $where ORDER BY $sortCol $ord NULLS LAST, id LIMIT :limit OFFSET :offset",
            params + mapOf("limit" to size, "offset" to (page - 1) * size),
            MOVIE_MAPPER,
        )
        val acts = items.associate { it.id to findActresses(it.id) }
        return Page(items, acts, total)
    }

    fun numbersNeedingScrape(): List<String> =
        jdbc.queryForList(
            "SELECT m.number FROM movies m WHERE m.status IN ('pending', 'failed') ORDER BY m.id",
            emptyMap<String, Any>(), String::class.java,
        ).filterNotNull()

    fun numbersReadyForOrganize(): List<String> =
        jdbc.queryForList(
            """
            SELECT DISTINCT m.number FROM movies m
            JOIN file_records fr ON fr.number = m.number
            WHERE m.status = 'scraped' AND fr.organize_status = 'pending'
            ORDER BY m.number
            """,
            emptyMap<String, Any>(), String::class.java,
        ).filterNotNull()

    fun touch(number: String) {
        jdbc.update(
            "UPDATE movies SET updated_at = CURRENT_TIMESTAMP WHERE number = :n", mapOf("n" to number),
        )
    }
}
