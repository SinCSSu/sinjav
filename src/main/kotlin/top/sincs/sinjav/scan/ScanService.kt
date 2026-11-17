package top.sincs.sinjav.scan

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import top.sincs.sinjav.config.AppProperties
import top.sincs.sinjav.domain.FileRecordRepository
import top.sincs.sinjav.domain.JobRepository
import top.sincs.sinjav.domain.MovieRepository

data class ScanParams(
    val sourceDirs: List<String>,
    val recursive: Boolean = true,
    val extensions: Set<String> = emptySet(),
    val minFileSizeMb: Long? = null,
)

@Service
class ScanService(
    private val scanner: DirectoryScanner,
    private val recognizer: NumberRecognizer,
    private val fileRecords: FileRecordRepository,
    private val movies: MovieRepository,
    private val jobs: JobRepository,
    private val props: AppProperties,
) {

    private val log = LoggerFactory.getLogger(ScanService::class.java)

    /**
     * 执行一次扫描（同步），负责任务进度更新。
     * 指纹（大小 + mtime）未变化的文件跳过，支持增量扫描。
     */
    fun execute(jobId: Long, params: ScanParams) {
        val minSize = params.minFileSizeMb ?: props.scan.minFileSizeMb
        val files = try {
            scanner.scan(params.sourceDirs, params.recursive, params.extensions, minSize)
        } catch (e: Exception) {
            log.error("扫描目录失败", e)
            jobs.appendError(jobId, "scan", "目录遍历失败: ${e.message}")
            jobs.finish(jobId, "failed")
            return
        }

        jobs.setTotalItems(jobId, files.size)
        var done = 0
        var failed = 0
        files.forEach { f ->
            try {
                val record = fileRecords.upsertOnScan(f.sourcePath, f.fileName, f.fileSize, f.fileMtime)
                // 指纹未变且已识别过 → 跳过（增量）
                val unchanged = record.fileSize == f.fileSize &&
                    record.fileMtime?.toEpochMilli() == f.fileMtime?.toEpochMilli() &&
                    record.recognizeStatus == "recognized"
                if (!unchanged) {
                    val recognized = recognizer.recognize(f.fileName)
                    if (recognized != null) {
                        fileRecords.updateRecognize(record.id, recognized.normalized, "recognized")
                        movies.ensurePending(recognized.normalized, recognized.raw)
                    } else {
                        fileRecords.updateRecognize(record.id, null, "unrecognized")
                    }
                }
            } catch (e: Exception) {
                failed++
                log.error("处理文件失败 {}: {}", f.sourcePath, e.message)
                jobs.appendError(jobId, f.sourcePath, e.message ?: "unknown")
            }
            done++
            if (done % 20 == 0 || done == files.size) jobs.updateProgress(jobId, done, failed)
        }
        jobs.finish(jobId, "completed")
        log.info("扫描完成 jobId={} 共 {} 个文件", jobId, files.size)
    }
}
