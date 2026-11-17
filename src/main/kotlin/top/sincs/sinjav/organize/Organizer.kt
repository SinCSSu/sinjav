package top.sincs.sinjav.organize

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import top.sincs.sinjav.config.AppProperties
import top.sincs.sinjav.domain.FileRecord
import top.sincs.sinjav.domain.FileRecordRepository
import top.sincs.sinjav.domain.Movie
import top.sincs.sinjav.domain.MovieRepository
import top.sincs.sinjav.scraper.HttpClientFactory
import java.nio.file.Files
import java.nio.file.Path

data class OrganizeParams(
    val targetDir: String? = null,
    val mode: String? = null,
    val namingTemplate: String? = null,
    val conflictPolicy: String? = null,
    val generateNfo: Boolean? = null,
    val downloadImages: Boolean? = null,
    val numbers: List<String>? = null,
)

data class OrganizeResult(val ok: Boolean, val skipped: Boolean = false, val message: String? = null)

/**
 * 整理编排：渲染命名模板 → 执行链接策略 → 写 nfo → 下载 poster/fanart/剧照 → 回写 file_records。
 */
@Component
class Organizer(
    private val naming: NamingTemplate,
    private val nfoWriter: NfoWriter,
    private val http: HttpClientFactory,
    private val movies: MovieRepository,
    private val fileRecords: FileRecordRepository,
    private val props: AppProperties,
) {

    private val log = LoggerFactory.getLogger(Organizer::class.java)

    fun executeOne(record: FileRecord, params: OrganizeParams): OrganizeResult {
        val number = record.number
            ?: return OrganizeResult(false, message = "文件未识别出番号")
        val movie = movies.findByNumber(number)
            ?: return OrganizeResult(false, message = "番号 $number 无元数据（未刮削）")
        if (movie.status != "scraped") return OrganizeResult(false, message = "番号 $number 未成功刮削")

        val mode = params.mode ?: props.organize.mode
        val strategy = strategyOf(mode)
        val conflict = ConflictPolicy.valueOf(
            (params.conflictPolicy ?: props.organize.conflictPolicy).uppercase(),
        )
        val template = params.namingTemplate ?: props.organize.namingTemplate
        val targetRoot = Path.of(params.targetDir?.takeIf { it.isNotBlank() } ?: props.organize.targetDir)

        val source = Path.of(record.sourcePath)
        if (mode != "move" && !Files.exists(source)) {
            fileRecords.updateOrganizeResult(record.id, null, mode, "failed", "源文件不存在")
            return OrganizeResult(false, message = "源文件不存在: $source")
        }

        return try {
            // 模板渲染结果：除最后一段外为子目录，最后一段为目标文件基名（扩展名沿用源文件）
            val rendered = naming.render(template, movie, movies.findActresses(movie.id))
            val parts = rendered.split('/').filter { it.isNotBlank() }
            val subDir = if (parts.size > 1) targetRoot.resolve(parts.dropLast(1).joinToString("/")) else targetRoot
            val baseName = parts.lastOrNull() ?: record.fileName.substringBeforeLast('.')
            val ext = record.fileName.substringAfterLast('.', "")
            val targetName = if (ext.isNotBlank()) "$baseName.$ext" else baseName
            val target = subDir.resolve(naming.sanitizeFileName(targetName))

            val placed = strategy.place(source, target, conflict)
            val generateNfo = params.generateNfo ?: props.organize.generateNfo
            val downloadImages = params.downloadImages ?: props.organize.downloadImages

            if (generateNfo) {
                nfoWriter.write(subDir, movie, movies.findActresses(movie.id), movies.findGenres(movie.id))
            }
            if (downloadImages) downloadImages(subDir, movie, mode)

            fileRecords.updateOrganizeResult(record.id, placed.toString(), mode, "organized", null)
            OrganizeResult(true)
        } catch (e: OrganizeSkipException) {
            fileRecords.updateOrganizeResult(record.id, record.targetPath, mode, "skipped", e.message)
            OrganizeResult(false, skipped = true, message = e.message)
        } catch (e: Exception) {
            log.warn("整理失败 {}: {}", record.sourcePath, e.message)
            fileRecords.updateOrganizeResult(record.id, null, mode, "failed", e.message)
            OrganizeResult(false, message = e.message ?: "unknown")
        }
    }

    private fun downloadImages(subDir: Path, movie: Movie, mode: String) {
        val ref = movie.coverUrl
        // move 模式下封面也可能已存在于源目录，但直接重新下载最简单可靠
        movie.coverUrl?.let { url ->
            runCatching { Files.write(subDir.resolve("poster.jpg"), http.download(url, referer = ref)) }
                .onFailure { log.warn("下载封面失败 {}: {}", url, it.message) }
            runCatching { Files.write(subDir.resolve("fanart.jpg"), http.download(url, referer = ref)) }
                .onFailure { log.warn("下载 fanart 失败 {}: {}", url, it.message) }
        }
        movie.previewUrls.forEachIndexed { i, url ->
            runCatching { Files.write(subDir.resolve("preview${i + 1}.jpg"), http.download(url, referer = ref)) }
                .onFailure { log.warn("下载剧照失败 {}: {}", url, it.message) }
        }
    }

    private fun strategyOf(mode: String): LinkStrategy = when (mode.lowercase()) {
        "hardlink" -> HardLinkStrategy()
        "symlink" -> SymLinkStrategy()
        "move" -> MoveStrategy()
        "copy" -> CopyStrategy()
        else -> throw IllegalArgumentException("未知整理方式: $mode")
    }
}
