package top.sincs.sinjav.scraper

import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import top.sincs.sinjav.config.AppProperties
import java.nio.file.Files
import java.nio.file.Path

/**
 * 刮削成功后下载封面/剧照到本地目录（data/images/{番号}/poster.jpg、previewN.jpg）。
 * 下载失败仅记录警告，不影响刮削结果。整理时可直接复用本地文件。
 */
@Component
class ImageStore(
    private val http: HttpClientFactory,
    private val props: AppProperties,
    private val coverCropper: CoverCropper,
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
     * 各图片字段的实际来源数据源（为空回退 source）。下载路由按设置的过盾数据源生效。
     */
    fun downloadAll(
        number: String,
        coverUrl: String?,
        previewUrls: List<String>,
        source: String? = null,
        coverSource: String? = null,
        previewSource: String? = null,
    ) {
        if (!props.images.download) return
        val dir = Path.of(props.images.dir).resolve(number)
        runCatching { Files.createDirectories(dir) }
            .onFailure { log.warn("创建图片目录失败 {}: {}", dir, it.message); return }

        coverUrl?.let { url ->
            runCatching {
                // 横版全封套扫描裁取左侧前封面 → 竖版 poster（FC2 单图封面不裁，见 CoverCropper）
                val bytes = coverCropper.cropFrontCover(number, http.download(url, source = coverSource ?: source))
                Files.write(dir.resolve("poster.jpg"), bytes)
            }.onFailure { log.warn("下载封面到本地失败 {}: {}", url, it.message) }
        }
        previewUrls.forEachIndexed { i, url ->
            runCatching { Files.write(dir.resolve("preview${i + 1}.jpg"), http.download(url, source = previewSource ?: source)) }
                .onFailure { log.warn("下载剧照到本地失败 {}: {}", url, it.message) }
        }
        log.info("图片已缓存 番号={} 目录={} 封面={} 剧照={}张", number, dir, coverUrl != null, previewUrls.size)
    }

    /**
     * 手动落库：用户上传的封面/剧照字节直接写入本地图片目录（不受 images.download 开关限制，
     * 上传即用户明确意图）。同名文件覆盖：先下载 URL 再写上传文件时上传文件优先。
     */
    fun saveLocal(number: String, cover: ByteArray?, previews: List<ByteArray>) {
        if (cover == null && previews.isEmpty()) return
        val dir = Path.of(props.images.dir).resolve(number)
        runCatching { Files.createDirectories(dir) }
            .onFailure { log.warn("创建图片目录失败 {}: {}", dir, it.message); return }
        cover?.let { runCatching { Files.write(dir.resolve("poster.jpg"), it) }.onFailure { log.warn("保存上传封面失败: {}", it.message) } }
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
