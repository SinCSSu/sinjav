package top.sincs.sinjav.scan

import org.springframework.stereotype.Service
import top.sincs.sinjav.domain.FileRecordRepository
import top.sincs.sinjav.domain.MovieRepository
import java.time.Instant

/** 落库条目（来自前端预览结果中保留的文件） */
data class ScanCommitItem(
    val sourcePath: String,
    val fileName: String,
    val fileSize: Long,
    val fileMtime: Instant? = null,
)

data class ScanCommitResult(val total: Long, val recognized: Long, val unrecognized: Long)

/**
 * 扫描确认落库：将预览扫描中用户保留的文件直接写入 file_records 并创建 pending 影片记录。
 * 复用 upsertOnScan 的指纹判断，重复提交幂等。
 */
@Service
class ScanCommitService(
    private val fileRecords: FileRecordRepository,
    private val movies: MovieRepository,
    private val recognizer: NumberRecognizer,
) {

    fun commit(items: List<ScanCommitItem>): ScanCommitResult {
        var recognized = 0L
        var unrecognized = 0L
        for (item in items) {
            val record = fileRecords.upsertOnScan(item.sourcePath, item.fileName, item.fileSize, item.fileMtime)
            val hit = recognizer.recognize(item.fileName)
            if (hit != null) {
                fileRecords.updateRecognize(record.id, hit.normalized, "recognized")
                movies.ensurePending(hit.normalized, hit.raw)
                recognized++
            } else {
                fileRecords.updateRecognize(record.id, null, "unrecognized")
                unrecognized++
            }
        }
        return ScanCommitResult(items.size.toLong(), recognized, unrecognized)
    }
}
