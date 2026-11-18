package top.sincs.sinjav.scan

import org.springframework.stereotype.Service
import top.sincs.sinjav.config.AppProperties
import java.time.Instant

/** 预览条目 */
data class ScanPreviewItem(
    val sourcePath: String,
    val fileName: String,
    val fileSize: Long,
    val fileMtime: Instant?,
    val number: String?,
    val recognizeStatus: String,
)

/** 预览扫描响应 */
data class ScanPreviewResponse(
    val items: List<ScanPreviewItem>,
    val total: Long,
    val truncated: Boolean,
)

/**
 * 预览扫描：同步遍历目录返回候选文件列表（不写数据库）。
 * extensions/minFileSizeMb 未指定（null 或 0）时回退到全局配置，与正式扫描同一规则。
 */
@Service
class ScanPreviewService(
    private val props: AppProperties,
    private val scanner: DirectoryScanner,
    private val recognizer: NumberRecognizer,
    private val cap: Int = 5000,
) {

    fun preview(
        sourceDirs: List<String>,
        recursive: Boolean,
        extensions: Set<String>?,
        minFileSizeMb: Long?,
        filenamePattern: String?,
    ): ScanPreviewResponse {
        val regex = filenamePattern?.let { runCatching { Regex(it) }.getOrElse { e ->
            throw IllegalArgumentException("文件名正则非法: ${e.message}")
        } }
        val effectiveMinMb = minFileSizeMb?.takeIf { it > 0 } ?: props.scan.minFileSizeMb
        val files = scanner.scan(
            sourceDirs, recursive,
            extensions ?: emptySet(),
            effectiveMinMb,
        )
        val filtered = if (regex != null) files.filter { regex.containsMatchIn(it.fileName) } else files
        val items = filtered.take(cap).map { f ->
            val number = recognizer.recognize(f.fileName)?.normalized
            ScanPreviewItem(
                sourcePath = f.sourcePath,
                fileName = f.fileName,
                fileSize = f.fileSize,
                fileMtime = f.fileMtime,
                number = number,
                recognizeStatus = if (number != null) "recognized" else "unrecognized",
            )
        }
        return ScanPreviewResponse(items = items, total = filtered.size.toLong(), truncated = filtered.size > cap)
    }
}
