package top.sincs.sinjav.domain

import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.Timestamp
import java.time.Instant

private val FILE_RECORD_MAPPER = RowMapper { rs, _ ->
    FileRecord(
        id = rs.getLong("id"),
        sourcePath = rs.getString("source_path"),
        fileName = rs.getString("file_name"),
        fileSize = rs.getLong("file_size"),
        fileMtime = rs.getTimestamp("file_mtime")?.toInstant(),
        number = rs.getString("number"),
        recognizeStatus = rs.getString("recognize_status"),
        targetPath = rs.getString("target_path"),
        linkMode = rs.getString("link_mode"),
        organizeStatus = rs.getString("organize_status"),
        organizeError = rs.getString("organize_error"),
        createdAt = rs.getTimestamp("created_at").toInstant(),
    )
}

@Repository
class FileRecordRepository(private val jdbc: NamedParameterJdbcTemplate) {

    fun findBySourcePath(sourcePath: String): FileRecord? =
        jdbc.query(
            "SELECT * FROM file_records WHERE source_path = :p",
            mapOf("p" to sourcePath),
            FILE_RECORD_MAPPER,
        ).firstOrNull()

    fun findByNumber(number: String): List<FileRecord> =
        jdbc.query(
            "SELECT * FROM file_records WHERE number = :n ORDER BY id",
            mapOf("n" to number),
            FILE_RECORD_MAPPER,
        )

    data class Page(val items: List<FileRecord>, val total: Long)

    fun list(
        page: Int,
        size: Int,
        number: String?,
        recognizeStatus: String?,
        organizeStatus: String?,
    ): Page {
        val conditions = mutableListOf<String>()
        val params = mutableMapOf<String, Any>()
        if (!number.isNullOrBlank()) { conditions += "number = :number"; params["number"] = number }
        if (!recognizeStatus.isNullOrBlank()) { conditions += "recognize_status = :rs"; params["rs"] = recognizeStatus }
        if (!organizeStatus.isNullOrBlank()) { conditions += "organize_status = :os"; params["os"] = organizeStatus }
        val where = if (conditions.isEmpty()) "" else "WHERE ${conditions.joinToString(" AND ")}"

        val total = jdbc.queryForObject(
            "SELECT count(*) FROM file_records $where", params, Long::class.java
        ) ?: 0L
        val q = mapOf("limit" to size, "offset" to (page - 1) * size)
        val items = jdbc.query(
            "SELECT * FROM file_records $where ORDER BY id LIMIT :limit OFFSET :offset",
            params + q, FILE_RECORD_MAPPER,
        )
        return Page(items, total)
    }

    /** 未识别出番号或尚未整理的文件是否需要重新扫描：按指纹判断 */
    fun upsertOnScan(sourcePath: String, fileName: String, fileSize: Long, fileMtime: Instant?): FileRecord {
        val existing = findBySourcePath(sourcePath)
        if (existing != null && existing.fileSize == fileSize &&
            existing.fileMtime?.toEpochMilli() == fileMtime?.toEpochMilli()
        ) return existing
        jdbc.update(
            """
            INSERT INTO file_records (source_path, file_name, file_size, file_mtime)
            VALUES (:sp, :fn, :fs, :fm)
            ON CONFLICT (source_path) DO UPDATE SET
                file_name = EXCLUDED.file_name,
                file_size = EXCLUDED.file_size,
                file_mtime = EXCLUDED.file_mtime
            """,
            mapOf(
                "sp" to sourcePath, "fn" to fileName, "fs" to fileSize,
                "fm" to fileMtime?.let { Timestamp.from(it) },
            ),
        )
        return findBySourcePath(sourcePath)!!
    }

    fun updateRecognize(id: Long, number: String?, recognizeStatus: String) {
        jdbc.update(
            "UPDATE file_records SET number = :n, recognize_status = :rs, updated_at = CURRENT_TIMESTAMP WHERE id = :id",
            mapOf("n" to number, "rs" to recognizeStatus, "id" to id),
        )
    }

    fun updateOrganizeResult(id: Long, targetPath: String?, linkMode: String?, status: String, error: String?) {
        jdbc.update(
            """
            UPDATE file_records
            SET target_path = :tp, link_mode = :lm, organize_status = :os, organize_error = :err,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = :id
            """,
            mapOf("tp" to targetPath, "lm" to linkMode, "os" to status, "err" to error, "id" to id),
        )
    }
}
