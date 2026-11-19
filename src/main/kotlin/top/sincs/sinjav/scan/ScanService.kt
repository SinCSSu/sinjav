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
    val excludePaths: Set<String> = emptySet(),
    val filenamePattern: String? = null,
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
        log.info(
            "扫描开始 jobId={} 目录={} recursive={} 最小体积={}MB 扩展名={}",
            jobId, params.sourceDirs, params.recursive, minSize,
            params.extensions.ifEmpty { "全部" },
        )
        val files = try {
            applyFilters(
                scanner.scan(params.sourceDirs, params.recursive, params.extensions, minSize),
                params.excludePaths, params.filenamePattern,
            )
        } catch (e: IllegalArgumentException) {
            jobs.appendError(jobId, "scan", e.message ?: "非法参数")
            jobs.finish(jobId, "failed")
            return
        } catch (e: Exception) {
            log.error("扫描目录失败", e)
            jobs.appendError(jobId, "scan", "目录遍历失败: ${e.message}")
            jobs.finish(jobId, "failed")
            return
        }
        log.info(
            "扫描过滤完成 jobId={} 遍历后保留 {} 个文件（排除清单 {} 条，文件名正则 {}）",
            jobId, files.size, params.excludePaths.size, params.filenamePattern ?: "无",
        )

        jobs.setTotalItems(jobId, files.size)
        var done = 0
        var failed = 0
        var recognizedCount = 0
        var unrecognizedCount = 0
        var skippedCount = 0
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
                        recognizedCount++
                        log.info("识别成功 {} ← {}", recognized.normalized, f.fileName)
                    } else {
                        fileRecords.updateRecognize(record.id, null, "unrecognized")
                        unrecognizedCount++
                        log.debug("无法识别番号: {}", f.fileName)
                    }
                } else {
                    skippedCount++
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
        log.info(
            "扫描完成 jobId={} 文件={} 新识别={} 未识别={} 增量跳过={} 失败={}",
            jobId, files.size, recognizedCount, unrecognizedCount, skippedCount, failed,
        )
    }

    companion object {
        /** 预览与正式扫描共用的过滤：排除清单 + 文件名正则（非法正则抛 IllegalArgumentException） */
        internal fun applyFilters(
            files: List<ScannedFile>,
            excludePaths: Set<String>,
            filenamePattern: String?,
        ): List<ScannedFile> {
            val regex = filenamePattern?.let { p ->
                runCatching { Regex(p) }.getOrElse { throw IllegalArgumentException("文件名正则非法: ${it.message}") }
            }
            return files.asSequence()
                .filter { it.sourcePath !in excludePaths }
                .filter { regex == null || regex.containsMatchIn(it.fileName) }
                .toList()
        }
    }
}
