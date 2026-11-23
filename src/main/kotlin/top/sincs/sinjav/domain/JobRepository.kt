package top.sincs.sinjav.domain

import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.PreparedStatementCreator
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.support.GeneratedKeyHolder
import org.springframework.stereotype.Repository
import java.sql.Timestamp
import java.time.Instant

private val JOB_MAPPER = RowMapper { rs, _ ->
    Job(
        id = rs.getLong("id"),
        type = rs.getString("type"),
        status = rs.getString("status"),
        params = rs.getString("params"),
        totalItems = rs.getInt("total_items"),
        doneItems = rs.getInt("done_items"),
        failItems = rs.getInt("fail_items"),
        errorDetail = rs.getString("error_detail"),
        startedAt = rs.getTimestamp("started_at").toInstant(),
        finishedAt = rs.getTimestamp("finished_at")?.toInstant(),
    )
}

@Repository
class JobRepository(private val jdbc: NamedParameterJdbcTemplate) {

    private val log = LoggerFactory.getLogger(JobRepository::class.java)

    /** 创建任务并返回（KeyHolder 获取自增 id，双方言兼容：SQLite/PostgreSQL） */
    fun create(type: String, paramsJson: String?, totalItems: Int): Job {
        val keyHolder = GeneratedKeyHolder()
        val psc = PreparedStatementCreator { con ->
            con.prepareStatement(
                "INSERT INTO jobs (type, params, total_items) VALUES (?, ?, ?)",
                java.sql.Statement.RETURN_GENERATED_KEYS,
            ).apply {
                setString(1, type)
                setString(2, paramsJson)
                setInt(3, totalItems)
            }
        }
        jdbc.jdbcOperations.update(psc, keyHolder)
        // SQLite getGeneratedKeys 的列名为 last_insert_rowid()，PostgreSQL 为 id，取首个数值列即可
        val id = keyHolder.keys?.values?.firstOrNull() as? Number
            ?: throw IllegalStateException("创建任务失败：未获取到自增 id")
        return findById(id.toLong())!!
    }

    fun findById(id: Long): Job? =
        jdbc.query("SELECT * FROM jobs WHERE id = :id", mapOf("id" to id), JOB_MAPPER).firstOrNull()

    data class Page(val items: List<Job>, val total: Long)

    fun list(page: Int, size: Int, type: String?, status: String?): Page {
        val conditions = mutableListOf<String>()
        val params = mutableMapOf<String, Any>()
        if (!type.isNullOrBlank()) { conditions += "type = :t"; params["t"] = type }
        if (!status.isNullOrBlank()) { conditions += "status = :s"; params["s"] = status }
        val where = if (conditions.isEmpty()) "" else "WHERE ${conditions.joinToString(" AND ")}"
        val total = jdbc.queryForObject("SELECT count(*) FROM jobs $where", params, Long::class.java) ?: 0L
        val items = jdbc.query(
            "SELECT * FROM jobs $where ORDER BY id DESC LIMIT :limit OFFSET :offset",
            params + mapOf("limit" to size, "offset" to (page - 1) * size), JOB_MAPPER,
        )
        return Page(items, total)
    }

    fun updateProgress(id: Long, doneItems: Int, failItems: Int) {
        jdbc.update(
            "UPDATE jobs SET done_items = :d, fail_items = :f WHERE id = :id",
            mapOf("d" to doneItems, "f" to failItems, "id" to id),
        )
    }

    fun setTotalItems(id: Long, totalItems: Int) {
        jdbc.update("UPDATE jobs SET total_items = :ti WHERE id = :id", mapOf("ti" to totalItems, "id" to id))
    }

    /** 读改写合并，影片级并发刮削下可能多线程同时追加，需同步避免丢记录 */
    @Synchronized
    fun appendError(id: Long, item: String, reason: String) {
        val existing = findById(id)?.errorDetail
        val entry = "\"${jsonEscape(item)}\": \"${jsonEscape(reason)}\""
        val merged = if (existing.isNullOrBlank()) "{${entry}}" else existing.trim().removePrefix("{").removeSuffix("}") + ", $entry}"
        jdbc.update("UPDATE jobs SET error_detail = :e WHERE id = :id", mapOf("e" to merged, "id" to id))
    }

    fun finish(id: Long, status: String) {
        jdbc.update(
            "UPDATE jobs SET status = :s, finished_at = :ft WHERE id = :id",
            mapOf("s" to status, "ft" to Timestamp.from(Instant.now()), "id" to id),
        )
        log.info("任务结束 jobId={} status={}", id, status)
    }

    /**
     * 任务是否已被取消（jobs.status = 'cancelled'）。
     *
     * 与内存态 [top.sincs.sinjav.job.JobControl] 的区别：这里查的是**已落库**的取消结果，
     * 覆盖「任务被停止后由执行侧落库」与「重启恢复后标记」两种场景；
     * 运行中的停止请求仍以 JobControl 为准（两者取或）。
     */
    fun isCancelled(id: Long): Boolean = findById(id)?.status == "cancelled"

    fun existsRunning(type: String): Boolean =
        (jdbc.queryForObject(
            "SELECT count(*) FROM jobs WHERE type = :t AND status = 'running'",
            mapOf("t" to type), Long::class.java,
        ) ?: 0L) > 0

    /** 全部运行中的任务（应用启动恢复用：容器重建后这些任务已不存在于内存） */
    fun findRunning(): List<Job> =
        jdbc.query(
            "SELECT * FROM jobs WHERE status = 'running' ORDER BY id",
            emptyMap<String, Any>(), JOB_MAPPER,
        )

    private fun jsonEscape(s: String): String = s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
}
