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

    fun markScraped(
        number: String,
        title: String?, titleZh: String?, titleOrig: String?, releaseDate: LocalDate?, director: String?,
        maker: String?, label: String?, series: String?, durationMin: Int?,
        coverUrl: String?, previewUrls: List<String>, plot: String?,
        source: String, actresses: List<String>, genres: List<String>,
    ) {
        val params = mapOf(
            "n" to number, "t" to title, "tz" to titleZh, "to" to titleOrig,
            "rd" to releaseDate?.let { Date.valueOf(it) }, "d" to director, "mk" to maker,
            "lb" to label, "se" to series, "du" to durationMin, "cv" to coverUrl,
            "pv" to previewUrls.joinToString(",", "[", "]") { "\"${it.replace("\"", "\\\"")}\"" },
            "pl" to plot, "src" to source,
        )
        jdbc.update(
            """
            UPDATE movies SET
                title = :t, title_zh = :tz, title_orig = :to, release_date = :rd, director = :d, maker = :mk,
                label = :lb, series = :se, duration_min = :du, cover_url = :cv, preview_urls = :pv,
                plot = :pl, source = :src, status = 'scraped', fail_reason = NULL,
                scraped_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
            WHERE number = :n
            """, params,
        )
        val movieId = findByNumber(number)?.id ?: return
        actresses.forEach { name -> linkActress(movieId, name) }
        genres.forEach { name -> linkGenre(movieId, name) }
    }

    fun markFailed(number: String, reason: String) {
        jdbc.update(
            "UPDATE movies SET status = 'failed', fail_reason = :r, updated_at = CURRENT_TIMESTAMP WHERE number = :n",
            mapOf("n" to number, "r" to reason),
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

    private fun linkActress(movieId: Long, name: String) {
        jdbc.update(
            "INSERT INTO actresses (name) VALUES (:nm) ON CONFLICT (name) DO NOTHING", mapOf("nm" to name),
        )
        jdbc.update(
            """
            INSERT INTO movie_actresses (movie_id, actress_id)
            SELECT :mid, id FROM actresses WHERE name = :nm
            ON CONFLICT DO NOTHING
            """,
            mapOf("mid" to movieId, "nm" to name),
        )
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
