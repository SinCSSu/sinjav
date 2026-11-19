package top.sincs.sinjav.scan

import org.slf4j.LoggerFactory
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

    private val log = LoggerFactory.getLogger(ScanCommitService::class.java)

    fun commit(items: List<ScanCommitItem>): ScanCommitResult {
        log.info("扫描预览落库开始 共 {} 条", items.size)
        var recognized = 0L
        var unrecognized = 0L
        for (item in items) {
            val record = fileRecords.upsertOnScan(item.sourcePath, item.fileName, item.fileSize, item.fileMtime)
            val hit = recognizer.recognize(item.fileName)
            if (hit != null) {
                fileRecords.updateRecognize(record.id, hit.normalized, "recognized")
                movies.ensurePending(hit.normalized, hit.raw)
                recognized++
                log.info("落库识别成功 {} ← {}", hit.normalized, item.fileName)
            } else {
                fileRecords.updateRecognize(record.id, null, "unrecognized")
                unrecognized++
            }
        }
        log.info("扫描预览落库完成 总计={} 识别={} 未识别={}", items.size, recognized, unrecognized)
        return ScanCommitResult(items.size.toLong(), recognized, unrecognized)
    }
}
