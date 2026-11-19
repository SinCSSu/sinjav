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

data class OrganizeItem(
    val recordId: Long,
    val targetPath: String? = null,
    val mode: String? = null,
)

data class OrganizeParams(
    val targetDir: String? = null,
    val mode: String? = null,
    val namingTemplate: String? = null,
    val conflictPolicy: String? = null,
    val generateNfo: Boolean? = null,
    val downloadImages: Boolean? = null,
    val numbers: List<String>? = null,
    /** 按文件记录逐条整理（可覆盖目标路径/整理方式），优先于 numbers */
    val items: List<OrganizeItem>? = null,
)

data class OrganizeResult(val ok: Boolean, val skipped: Boolean = false, val message: String? = null)

/** 整理预览项：计算目标路径但不执行，含重整所需的原结果信息 */
data class OrganizePreviewItem(
    val recordId: Long,
    val sourcePath: String,
    val fileName: String,
    val number: String?,
    val title: String?,
    val coverUrl: String?,
    val targetPath: String?,
    val mode: String?,
    val conflictPolicy: String?,
    val reorganize: Boolean = false,
    val currentTargetPath: String? = null,
    val currentLinkMode: String? = null,
    val error: String? = null,
)

data class OrganizePreviewResponse(val items: List<OrganizePreviewItem>)

/**
 * 整理编排：渲染命名模板 → 执行链接策略 → 写 nfo → 下载 poster/fanart/剧照 → 回写 file_records。
 * 支持预览（仅计算目标路径）与按文件记录逐条覆盖整理（含已整理记录的重整）。
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

    /** 预览：逐条计算目标路径，不写库不执行。已整理记录附带原结果信息（reorganize） */
    fun preview(recordIds: List<Long>, params: OrganizeParams): OrganizePreviewResponse =
        OrganizePreviewResponse(recordIds.map { previewOne(it, params) })

    private fun previewOne(recordId: Long, params: OrganizeParams): OrganizePreviewItem {
        val record = fileRecords.findById(recordId)
            ?: return OrganizePreviewItem(
                recordId = recordId, sourcePath = "", fileName = "", number = null,
                title = null, coverUrl = null, targetPath = null,
                mode = params.mode,
                conflictPolicy = params.conflictPolicy ?: props.organize.conflictPolicy,
                error = "文件记录不存在: $recordId",
            )
        val movie = record.number?.let { movies.findByNumber(it) }
        val mode = requiredMode(params)
        val reorganize = record.organizeStatus == "organized"
        val item = OrganizePreviewItem(
            recordId = recordId,
            sourcePath = record.sourcePath,
            fileName = record.fileName,
            number = record.number,
            title = movie?.title,
            coverUrl = movie?.coverUrl,
            targetPath = null,
            mode = mode,
            conflictPolicy = params.conflictPolicy ?: props.organize.conflictPolicy,
            reorganize = reorganize,
            currentTargetPath = record.targetPath,
            currentLinkMode = record.linkMode,
        )
        if (movie == null) return item.copy(error = "番号 ${record.number} 无元数据（未刮削）")
        if (movie.status != "scraped") return item.copy(error = "番号 ${movie.number} 未成功刮削")
        if (reorganize && record.linkMode == "move" && mode in LINK_MODES) {
            return item.copy(error = "原整理为移动，文件已不在源位置，重整不能使用链接模式")
        }
        val target = runCatching { computeTarget(record, movie, params, null) }
            .getOrElse { return item.copy(error = it.message ?: "目标路径计算失败") }
        return item.copy(targetPath = target.toString())
    }

    /** 执行单条整理（普通流程） */
    fun executeOne(
        record: FileRecord,
        params: OrganizeParams,
        overrideTargetPath: String? = null,
        overrideMode: String? = null,
    ): OrganizeResult {
        val number = record.number
            ?: return OrganizeResult(false, message = "文件未识别出番号")
        val movie = movies.findByNumber(number)
            ?: return OrganizeResult(false, message = "番号 $number 无元数据（未刮削）")
        if (movie.status != "scraped") return OrganizeResult(false, message = "番号 $number 未成功刮削")

        val mode = overrideMode ?: requiredMode(params)
        val strategy = strategyOf(mode)
        val conflict = ConflictPolicy.valueOf(
            (params.conflictPolicy ?: props.organize.conflictPolicy).uppercase(),
        )
        val targetRoot = requiredTargetDir(params)

        val source = Path.of(record.sourcePath)
        if (mode != "move" && !Files.exists(source)) {
            fileRecords.updateOrganizeResult(record.id, null, mode, "failed", "源文件不存在")
            return OrganizeResult(false, message = "源文件不存在: $source")
        }

        return try {
            val target = computeTarget(record, movie, params, overrideTargetPath)
            val subDir = target.parent ?: targetRoot

            val placed = strategy.place(source, target, conflict)
            val generateNfo = params.generateNfo ?: props.organize.generateNfo
            val downloadImages = params.downloadImages ?: props.organize.downloadImages

            if (generateNfo) {
                nfoWriter.write(subDir, movie, movies.findActresses(movie.id), movies.findGenres(movie.id))
            }
            if (downloadImages) downloadImages(subDir, movie, mode)

            fileRecords.updateOrganizeResult(record.id, placed.toString(), mode, "organized", null)
            log.info("整理完成 {} → {} (mode={})", record.sourcePath, placed, mode)
            OrganizeResult(true)
        } catch (e: OrganizeSkipException) {
            fileRecords.updateOrganizeResult(record.id, record.targetPath, mode, "skipped", e.message)
            log.info("整理跳过 {}: {}", record.sourcePath, e.message)
            OrganizeResult(false, skipped = true, message = e.message)
        } catch (e: Exception) {
            log.warn("整理失败 {}: {}", record.sourcePath, e.message)
            fileRecords.updateOrganizeResult(record.id, null, mode, "failed", e.message)
            OrganizeResult(false, message = e.message ?: "unknown")
        }
    }

    /**
     * 按文件记录整理（items 模式）。
     * 已整理记录视为重整：
     * - 原整理为链接/复制：删除旧目标文件与生成的元数据文件（空目录一并移除），再按新规则整理
     * - 原整理为移动：文件已不在源位置，以旧目标位置作为新源；且不能使用链接模式
     */
    fun executeItem(item: OrganizeItem, params: OrganizeParams): OrganizeResult {
        val record = fileRecords.findById(item.recordId)
            ?: return OrganizeResult(false, message = "文件记录不存在: ${item.recordId}")
        var rec = record
        if (rec.organizeStatus == "organized") {
            val effectiveMode = item.mode ?: requiredMode(params)
            when (rec.linkMode) {
                "move" -> {
                    if (effectiveMode in LINK_MODES) {
                        return OrganizeResult(false, message = "原整理为移动，文件已不在源位置，重整不能使用链接模式")
                    }
                    fileRecords.resetForReorganize(rec.id, rec.targetPath ?: rec.sourcePath)
                }
                else -> {
                    cleanupOldResult(rec.sourcePath, rec.targetPath)
                    fileRecords.resetForReorganize(rec.id, rec.sourcePath)
                }
            }
            rec = fileRecords.findById(rec.id) ?: return OrganizeResult(false, message = "文件记录不存在: ${item.recordId}")
        }
        return executeOne(rec, params, item.targetPath, item.mode)
    }

    /**
     * 计算目标路径。overrideTargetPath 非空时直接使用（完整目录+文件名；
     * 未带扩展名而源文件有时自动补上），否则按模板渲染。
     */
    private fun computeTarget(
        record: FileRecord,
        movie: Movie,
        params: OrganizeParams,
        overrideTargetPath: String?,
    ): Path {
        if (!overrideTargetPath.isNullOrBlank()) {
            val p = Path.of(overrideTargetPath.trim())
            val ext = record.fileName.substringAfterLast('.', "")
            val name = p.fileName.toString()
            return if (ext.isNotBlank() && name.substringAfterLast('.', "") != ext) {
                p.resolveSibling(naming.sanitizeFileName("$name.$ext"))
            } else {
                p.resolveSibling(naming.sanitizeFileName(name))
            }
        }
        val template = params.namingTemplate ?: props.organize.namingTemplate
        val targetRoot = requiredTargetDir(params)
        // 模板渲染结果：除最后一段外为子目录，最后一段为目标文件基名（扩展名沿用源文件）
        val rendered = naming.render(template, movie, movies.findActresses(movie.id))
        val parts = rendered.split('/').filter { it.isNotBlank() }
        val subDir = if (parts.size > 1) targetRoot.resolve(parts.dropLast(1).joinToString("/")) else targetRoot
        val baseName = parts.lastOrNull() ?: record.fileName.substringBeforeLast('.')
        val ext = record.fileName.substringAfterLast('.', "")
        val targetName = if (ext.isNotBlank()) "$baseName.$ext" else baseName
        return subDir.resolve(naming.sanitizeFileName(targetName))
    }

    /** 清理原整理结果（链接/复制模式）：删除目标文件与同目录生成的元数据文件，目录空则移除 */
    private fun cleanupOldResult(sourcePath: String, targetPath: String?) {
        if (targetPath.isNullOrBlank()) return
        val target = Path.of(targetPath)
        val sourceParent = Path.of(sourcePath).parent
        runCatching { Files.deleteIfExists(target) }
            .onFailure { log.warn("删除旧目标文件失败 {}: {}", targetPath, it.message) }
        val dir = target.parent ?: return
        if (dir == sourceParent) return
        val generated = setOf("movie.nfo", "poster.jpg", "fanart.jpg")
        runCatching {
            Files.list(dir).use { stream ->
                stream.forEach { p ->
                    val name = p.fileName.toString().lowercase()
                    if (name in generated || Regex("preview\\d+\\.jpg").matches(name)) {
                        runCatching { Files.deleteIfExists(p) }
                    }
                }
            }
        }
        runCatching {
            Files.list(dir).use { if (!it.findFirst().isPresent) Files.delete(dir) }
        }
    }

    private fun downloadImages(subDir: Path, movie: Movie, mode: String) {
        val ref = movie.coverUrl
        // move 模式下封面也可能已存在于源目录，但直接重新下载最简单可靠
        movie.coverUrl?.let { url ->
            runCatching { Files.write(subDir.resolve("poster.jpg"), http.download(url, referer = ref, source = movie.source)) }
                .onFailure { log.warn("下载封面失败 {}: {}", url, it.message) }
            runCatching { Files.write(subDir.resolve("fanart.jpg"), http.download(url, referer = ref, source = movie.source)) }
                .onFailure { log.warn("下载 fanart 失败 {}: {}", url, it.message) }
        }
        movie.previewUrls.forEachIndexed { i, url ->
            runCatching { Files.write(subDir.resolve("preview${i + 1}.jpg"), http.download(url, referer = ref, source = movie.source)) }
                .onFailure { log.warn("下载剧照失败 {}: {}", url, it.message) }
        }
    }

    /** 整理方式必填（不再回退全局配置） */
    private fun requiredMode(params: OrganizeParams): String =
        params.mode?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("整理方式（mode）必填，请每次整理时指定")

    /** 目标目录必填（不再回退全局配置） */
    private fun requiredTargetDir(params: OrganizeParams): Path =
        params.targetDir?.takeIf { it.isNotBlank() }?.let { Path.of(it.trim()) }
            ?: throw IllegalArgumentException("目标目录（targetDir）必填，请每次整理时指定")

    private fun strategyOf(mode: String): LinkStrategy = when (mode.lowercase()) {
        "hardlink" -> HardLinkStrategy()
        "symlink" -> SymLinkStrategy()
        "move" -> MoveStrategy()
        "copy" -> CopyStrategy()
        else -> throw IllegalArgumentException("未知整理方式: $mode")
    }

    companion object {
        /** 链接类模式：要求源文件仍存在于源位置 */
        val LINK_MODES = setOf("hardlink", "symlink")
    }
}
