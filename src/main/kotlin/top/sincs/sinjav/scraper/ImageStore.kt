package top.sincs.sinjav.scraper

import org.slf4j.LoggerFactory
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
) {

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
            runCatching { Files.write(dir.resolve("poster.jpg"), http.download(url, source = coverSource ?: source)) }
                .onFailure { log.warn("下载封面到本地失败 {}: {}", url, it.message) }
        }
        previewUrls.forEachIndexed { i, url ->
            runCatching { Files.write(dir.resolve("preview${i + 1}.jpg"), http.download(url, source = previewSource ?: source)) }
                .onFailure { log.warn("下载剧照到本地失败 {}: {}", url, it.message) }
        }
        log.info("图片已缓存 番号={} 目录={} 封面={} 剧照={}张", number, dir, coverUrl != null, previewUrls.size)
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
