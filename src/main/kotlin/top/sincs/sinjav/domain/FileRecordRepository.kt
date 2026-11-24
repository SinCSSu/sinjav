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

    fun findById(id: Long): FileRecord? =
        jdbc.query("SELECT * FROM file_records WHERE id = :id", mapOf("id" to id), FILE_RECORD_MAPPER).firstOrNull()

    /** 整理弹窗列表行：文件记录 LEFT JOIN 影片元数据（标题/封面） */
    data class OrganizeRecordRow(
        val id: Long,
        val sourcePath: String,
        val fileName: String,
        val fileSize: Long,
        val number: String?,
        val title: String?,
        val coverUrl: String?,
        val organizeStatus: String,
        val targetPath: String?,
        val linkMode: String?,
        val organizeError: String?,
    )

    private val ORGANIZE_RECORD_MAPPER = RowMapper { rs, _ ->
        OrganizeRecordRow(
            id = rs.getLong("id"),
            sourcePath = rs.getString("source_path"),
            fileName = rs.getString("file_name"),
            fileSize = rs.getLong("file_size"),
            number = rs.getString("number"),
            title = rs.getString("title"),
            coverUrl = rs.getString("cover_url"),
            organizeStatus = rs.getString("organize_status"),
            targetPath = rs.getString("target_path"),
            linkMode = rs.getString("link_mode"),
            organizeError = rs.getString("organize_error"),
        )
    }

    /**
     * 按整理状态分组列出文件记录（已联出影片标题/封面），keyword 模糊匹配番号/文件名/标题。
     * group=exists 为「已存在」分组：emby_items 快照中已有该番号、但本地尚未整理的视频；
     * group=pending 时排除这些番号，保证分组互斥。
     * group=failed 为「失败」分组：整理失败的记录（含未刮削/文件名超长等），可勾选重试。
     */
    fun listForOrganize(page: Int, size: Int, group: String, keyword: String?): Pair<List<OrganizeRecordRow>, Long> {
        val organizeStatus = when (group) {
            "organized" -> "organized"
            "organizing" -> "organizing"
            "failed" -> "failed"
            else -> "pending"
        }
        val conditions = mutableListOf("fr.recognize_status = 'recognized'", "fr.organize_status = :os")
        if (group == "exists") {
            conditions += "EXISTS (SELECT 1 FROM emby_items e WHERE e.number = fr.number)"
        } else if (group == "pending") {
            conditions += "NOT EXISTS (SELECT 1 FROM emby_items e WHERE e.number = fr.number)"
        }
        val params = mutableMapOf<String, Any>("os" to organizeStatus)
        if (!keyword.isNullOrBlank()) {
            conditions +=
                "(LOWER(fr.number) LIKE LOWER(:kw) OR LOWER(fr.file_name) LIKE LOWER(:kw) OR LOWER(m.title) LIKE LOWER(:kw))"
            params["kw"] = "%$keyword%"
        }
        val where = "WHERE ${conditions.joinToString(" AND ")}"
        val from = "FROM file_records fr LEFT JOIN movies m ON m.number = fr.number $where"
        val total = jdbc.queryForObject("SELECT count(*) $from", params, Long::class.java) ?: 0L
        val items = jdbc.query(
            """
            SELECT fr.id, fr.source_path, fr.file_name, fr.file_size, fr.number,
                   m.title AS title, m.cover_url AS cover_url,
                   fr.organize_status, fr.target_path, fr.link_mode, fr.organize_error
            $from ORDER BY fr.id LIMIT :limit OFFSET :offset
            """,
            params + mapOf("limit" to size, "offset" to (page - 1) * size),
            ORGANIZE_RECORD_MAPPER,
        )
        return items to total
    }


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

    /** 整理任务开始时批量标记为「整理中」（保留原 targetPath/linkMode 供重整判断） */
    fun markOrganizing(ids: Collection<Long>) {
        if (ids.isEmpty()) return
        jdbc.update(
            "UPDATE file_records SET organize_status = 'organizing', updated_at = CURRENT_TIMESTAMP " +
                "WHERE id IN (:ids)",
            mapOf("ids" to ids.toList()),
        )
    }

    /** 任务结束后把该任务标记但尚未落到最终状态的「整理中」记录复位为待整理（异常中断兜底） */
    fun resetOrganizingToPending(ids: Collection<Long>) {
        if (ids.isEmpty()) return
        jdbc.update(
            "UPDATE file_records SET organize_status = 'pending', updated_at = CURRENT_TIMESTAMP " +
                "WHERE organize_status = 'organizing' AND id IN (:ids)",
            mapOf("ids" to ids.toList()),
        )
    }

    /** 应用启动恢复：容器重建后已无任何执行线程，全部「整理中」记录复位为待整理。返回复位条数 */
    fun resetAllOrganizingToPending(): Int =
        jdbc.update(
            "UPDATE file_records SET organize_status = 'pending', updated_at = CURRENT_TIMESTAMP " +
                "WHERE organize_status = 'organizing'",
            emptyMap<String, Any>(),
        )

    /**
     * 重整已整理记录前的重置：
     * - move 整理的记录：源文件已在旧目标位置，将 source_path 指向旧 target_path
     * - 链接/复制整理的记录：newSourcePath 传原 source_path，仅清空整理结果
     */
    fun resetForReorganize(id: Long, newSourcePath: String) {
        jdbc.update(
            """
            UPDATE file_records
            SET source_path = :sp, target_path = NULL, link_mode = NULL,
                organize_status = 'pending', organize_error = NULL, updated_at = CURRENT_TIMESTAMP
            WHERE id = :id
            """,
            mapOf("sp" to newSourcePath, "id" to id),
        )
    }

    fun deleteByNumber(number: String): Int =
        jdbc.update("DELETE FROM file_records WHERE number = :n", mapOf("n" to number))
}
