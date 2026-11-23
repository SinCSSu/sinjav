package top.sincs.sinjav.scraper

import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import top.sincs.sinjav.config.AppProperties
import java.nio.file.Files
import java.nio.file.Path

/**
 * 刮削成功后下载封面/剧照到本地目录（data/images/{番号}/poster.jpg、fanart.jpg、previewN.jpg）。
 * 封面走候选列表（复刻 JavSP download_cover）：按 cover_candidates 顺序逐个尝试，
 * 下载失败或图片无效（valid_pic：格式魔数 + 最小体积 + 可解码校验）自动换下一候选；
 * javdb 封面已由 ScraperChain 固定沉底。下载失败仅记录警告，不影响刮削结果。
 * poster.jpg 为裁剪后的竖版海报，fanart.jpg 保留完整原图（整理时一并进入媒体库）。
 */
@Component
class ImageStore(
    private val http: HttpClientFactory,
    private val props: AppProperties,
    private val coverCropper: CoverCropper,
    private val movies: top.sincs.sinjav.domain.MovieRepository,
) {

    /** 每日 04:00 自动清理过期图片缓存（TTL 在设置页配置，0 = 永久保留） */
    @Scheduled(cron = "0 0 4 * * *", zone = "Asia/Shanghai")
    fun scheduledCleanup() {
        runCatching { cleanupExpired() }
            .onFailure { log.warn("图片缓存定时清理失败: {}", it.message) }
    }

    private val log = LoggerFactory.getLogger(ImageStore::class.java)

    /**
     * 下载封面/剧照。source 为整体来源（单源时）；coverSource/previewSource 为多源合并时
     * 各图片字段的实际来源数据源（为空回退 source）；coverCandidates 为封面候选列表
     * （为空时回退单候选 coverUrl）。下载路由按设置的过盾数据源生效。
     */
    fun downloadAll(
        number: String,
        coverUrl: String?,
        previewUrls: List<String>,
        source: String? = null,
        coverSource: String? = null,
        previewSource: String? = null,
        coverCandidates: List<CoverCandidate> = emptyList(),
    ) {
        if (!props.images.download) return
        val dir = Path.of(props.images.dir).resolve(number)
        runCatching { Files.createDirectories(dir) }
            .onFailure { log.warn("创建图片目录失败 {}: {}", dir, it.message); return }

        // 封面：候选列表逐个尝试（JavSP download_cover 语义），成功 → fanart 原图 + poster 裁剪
        downloadCoverOriginal(number, coverUrl, coverSource ?: source, coverCandidates)?.let { original ->
            runCatching {
                Files.write(dir.resolve("fanart.jpg"), original)
                // 横版全封套扫描裁取右侧前封面 → 竖版 poster（FC2 单图封面不裁，见 CoverCropper）
                Files.write(dir.resolve("poster.jpg"), coverCropper.cropFrontCover(number, original))
            }.onFailure { log.warn("封面写入本地失败 番号={}: {}", number, it.message) }
        }
        previewUrls.forEachIndexed { i, url ->
            runCatching { Files.write(dir.resolve("preview${i + 1}.jpg"), http.download(url, source = previewSource ?: source)) }
                .onFailure { log.warn("下载剧照到本地失败 {}: {}", url, it.message) }
        }
        log.info("图片已缓存 番号={} 目录={} 封面={} 剧照={}张", number, dir, coverUrl != null, previewUrls.size)
    }

    /**
     * 获取封面原图字节（候选列表逐个尝试，JavSP download_cover 同款语义）：
     * - candidates 非空 → 逐候选下载；某候选下载失败或图片无效（[isValidImage]）→ 换下一个；
     * - candidates 为空 → 回退单候选 (coverUrl, source)；
     * - 全部失败返回 null（不抛异常，不影响刮削流程）。
     * 成功后回写 cover_source 为实际生效候选的来源（候选回退时与落库首位来源可能不同）。
     * 供 [downloadAll]（写缓存）与 Organizer（写入媒体库目录）复用。
     */
    fun downloadCoverOriginal(
        number: String,
        coverUrl: String?,
        source: String?,
        candidates: List<CoverCandidate> = emptyList(),
    ): ByteArray? {
        // 候选缺省来源回退整体来源；无候选时以单 URL 构造
        val effective = candidates.ifEmpty {
            listOfNotNull(coverUrl?.let { CoverCandidate(it, source) })
        }.map { it.copy(source = it.source ?: source) }
        if (effective.isEmpty()) return null

        for ((index, candidate) in effective.withIndex()) {
            val bytes = runCatching { http.download(candidate.url, source = candidate.source) }
                .onFailure { log.debug("封面候选[{}]下载失败 {}: {}", index, candidate.url, it.message) }
                .getOrNull()
            if (bytes == null || !isValidImage(bytes)) {
                if (bytes != null) log.debug("封面候选[{}]图片无效（valid_pic 未通过）: {}", index, candidate.url)
                continue
            }
            log.info(
                "封面下载成功 番号={} 候选[{}/{}] 来源={} {}",
                number, index + 1, effective.size, candidate.source ?: "—", candidate.url,
            )
            if (candidate.source != null) movies.markCoverSource(number, candidate.source)
            return bytes
        }
        log.warn("封面下载失败 番号={}：全部 {} 个候选均不可用", number, effective.size)
        return null
    }

    /**
     * valid_pic 校验（借自 JavSP）：格式魔数可识别 + 可解码格式（JPEG/PNG/GIF/BMP）完整解码，
     * 拒绝 200 响应下的 HTML 错误页/占位图；WebP/AVIF 等魔数可识别但 ImageIO 不可解码的格式按魔数放行。
     *
     * 公开版：供头像等其他下载场景复用同一套判定（[downloadCoverOriginal] 内部同样走这里）。
     */
    fun validPic(bytes: ByteArray): Boolean = isValidImage(bytes)

    /** valid_pic 校验（文件版）：读取落盘文件后按 [validPic] 判定；读取失败视为无效 */
    fun validPic(path: Path): Boolean =
        runCatching { Files.readAllBytes(path) }.map { validPic(it) }.getOrDefault(false)

    /**
     * 下载单张图片并做 valid_pic 校验，返回字节；下载失败或不是有效图片返回 null（不抛异常）。
     * 与 [downloadAll] 的区别是不绑定 images.dir（供演员头像等需要自定义落盘位置的场景复用）。
     */
    fun downloadImage(url: String, referer: String? = null, source: String? = null, timeoutMs: Long? = null): ByteArray? {
        val bytes = runCatching { http.download(url, referer = referer, source = source, timeoutMs = timeoutMs) }
            .onFailure { log.warn("图片下载失败 {}: {}", url, it.message) }
            .getOrNull() ?: return null
        if (!validPic(bytes)) {
            log.debug("图片无效（valid_pic 未通过）: {}", url)
            return null
        }
        return bytes
    }

    private fun isValidImage(bytes: ByteArray): Boolean {
        val type = ImageFormats.detect(bytes) ?: return false
        return when (type) {
            "image/jpeg", "image/png", "image/gif", "image/bmp" ->
                runCatching { javax.imageio.ImageIO.read(java.io.ByteArrayInputStream(bytes)) != null }.getOrDefault(false)
            else -> true
        }
    }

    /**
     * 手动落库：用户上传的封面/剧照字节直接写入本地图片目录（不受 images.download 开关限制，
     * 上传即用户明确意图）。同名文件覆盖：先下载 URL 再写上传文件时上传文件优先；
     * 上传封面后删除可能残留的 URL 原图 fanart.jpg（避免海报与原图来源不一致）。
     */
    fun saveLocal(number: String, cover: ByteArray?, previews: List<ByteArray>) {
        if (cover == null && previews.isEmpty()) return
        val dir = Path.of(props.images.dir).resolve(number)
        runCatching { Files.createDirectories(dir) }
            .onFailure { log.warn("创建图片目录失败 {}: {}", dir, it.message); return }
        cover?.let {
            runCatching {
                Files.write(dir.resolve("poster.jpg"), it)
                Files.deleteIfExists(dir.resolve("fanart.jpg"))
            }.onFailure { log.warn("保存上传封面失败: {}", it.message) }
        }
        previews.forEachIndexed { i, bytes ->
            runCatching { Files.write(dir.resolve("preview${i + 1}.jpg"), bytes) }
                .onFailure { log.warn("保存上传剧照#{} 失败: {}", i + 1, it.message) }
        }
        log.info("上传图片已保存 番号={} 目录={} 封面={} 剧照={}张", number, dir, cover != null, previews.size)
    }

    /** 缓存清理结果：删除的文件数与字节数 */
    data class CleanupResult(val files: Int, val bytes: Long)

    /**
     * 清理过期图片缓存：按文件修改时间与 ttlDays 比较删除过期文件并移除空目录。
     * 重新刮削会重写文件（mtime 更新），活跃条目不受影响；ttlDays <= 0 表示永久保留，不做清理。
     */
    fun cleanupExpired(): CleanupResult {
        val ttlDays = props.images.ttlDays
        if (ttlDays <= 0) return CleanupResult(0, 0)
        val root = Path.of(props.images.dir)
        if (!Files.isDirectory(root)) return CleanupResult(0, 0)
        val expire = System.currentTimeMillis() - ttlDays * 24L * 3600_000L

        var files = 0
        var bytes = 0L
        Files.walk(root).use { stream ->
            stream.filter { Files.isRegularFile(it) }
                .filter {
                    runCatching { Files.getLastModifiedTime(it).toMillis() < expire }.getOrDefault(false)
                }
                .forEach { p ->
                    bytes += runCatching { Files.size(p) }.getOrDefault(0)
                    if (runCatching { Files.deleteIfExists(p) }.getOrDefault(false)) files++
                }
        }
        // 自底向上移除清空后的番号目录
        Files.walk(root).use { stream ->
            stream.filter { Files.isDirectory(it) && it != root }
                .sorted(Comparator.reverseOrder())
                .forEach { runCatching { Files.deleteIfExists(it) } }
        }
        if (files > 0) {
            log.info("图片缓存清理: TTL={}天, 删除 {} 个文件 ({})", ttlDays, files, formatSize(bytes))
        }
        return CleanupResult(files, bytes)
    }

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1 shl 30 -> "%.1f GB".format(bytes / 1e9)
        bytes >= 1 shl 20 -> "%.1f MB".format(bytes / 1e6)
        bytes >= 1 shl 10 -> "%.1f KB".format(bytes / 1e3)
        else -> "$bytes B"
    }

    /** 本地图片相对 Web 路径（/local-images/{番号}/文件名），不存在时由前端回退到代理 */
    fun localUrl(number: String, file: String): String = "/local-images/$number/$file"

    /** 读取本地缓存封面（poster.jpg），不存在返回 null（Emby 图片推送回退用） */
    fun readPoster(number: String): ByteArray? =
        runCatching { Path.of(props.images.dir).resolve(number).resolve("poster.jpg") }
            .map { if (Files.isRegularFile(it)) Files.readAllBytes(it) else null }
            .getOrNull()

    /** 读取本地缓存完整原图（fanart.jpg），不存在返回 null（整理复用缓存，避免重复下载） */
    fun readFanart(number: String): ByteArray? =
        runCatching { Path.of(props.images.dir).resolve(number).resolve("fanart.jpg") }
            .map { if (Files.isRegularFile(it)) Files.readAllBytes(it) else null }
            .getOrNull()

    /** 读取本地缓存剧照（preview1..N.jpg，按序号排序），无缓存返回空列表 */
    fun readPreviews(number: String): List<ByteArray> {
        val dir = Path.of(props.images.dir).resolve(number)
        if (!Files.isDirectory(dir)) return emptyList()
        return runCatching {
            Files.list(dir).use { stream ->
                stream.map { it.fileName.toString().lowercase() }
                    .filter { Regex("preview\\d+\\.jpg").matches(it) }
                    .sorted { a, b -> extractPreviewIndex(a) - extractPreviewIndex(b) }
                    .map { Files.readAllBytes(dir.resolve(it)) }
                    .toList()
            }
        }.getOrDefault(emptyList())
    }

    private fun extractPreviewIndex(name: String): Int =
        Regex("\\d+").find(name)?.value?.toIntOrNull() ?: 0

    /** 删除该番号的本地图片缓存目录（删除影片记录时调用，仅影响缓存，不涉及视频文件） */
    fun delete(number: String) {
        val dir = Path.of(props.images.dir).resolve(number)
        runCatching {
            if (Files.exists(dir)) {
                Files.list(dir).use { files -> files.forEach { Files.deleteIfExists(it) } }
                Files.deleteIfExists(dir)
            }
        }.onFailure { log.warn("删除本地图片目录失败 {}: {}", dir, it.message) }
    }
}
