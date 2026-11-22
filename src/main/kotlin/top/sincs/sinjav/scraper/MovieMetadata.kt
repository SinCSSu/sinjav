package top.sincs.sinjav.scraper

import org.slf4j.LoggerFactory
import java.time.LocalDate

/**
 * 封面候选（复刻 JavSP 的 covers 有序候选列表）：按刮削链优先级排序，
 * 下载失败/图片无效时自动换下一候选；javdb 封面（站点水印）固定沉底。
 */
data class CoverCandidate(
    val url: String,
    val source: String? = null,
)

/** 统一刮削结果模型 */
data class MovieMetadata(
    val number: String,
    val title: String? = null,
    val titleZh: String? = null,
    val releaseDate: LocalDate? = null,
    val director: String? = null,
    val maker: String? = null,
    val label: String? = null,
    val series: String? = null,
    val durationMin: Int? = null,
    val coverUrl: String? = null,
    val previewUrls: List<String> = emptyList(),
    // 图片字段的实际来源数据源 id（多源合并时用于按设置的过盾数据源路由图片下载）
    val coverSource: String? = null,
    val previewSource: String? = null,
    // 封面候选列表（含 coverUrl 本身，按优先级排序）；下载层沿候选逐个尝试
    val coverCandidates: List<CoverCandidate> = emptyList(),
    val plot: String? = null,
    val actresses: List<String> = emptyList(),
    val genres: List<String> = emptyList(),
)

class ScrapeException(message: String, cause: Throwable? = null) : Exception(message, cause)

interface Scraper {
    /** 数据源标识：javbus / javdb / missav */
    fun id(): String

    /** 按番号刮削元数据，失败抛 ScrapeException */
    @Throws(ScrapeException::class)
    fun scrape(number: String): MovieMetadata

    /**
     * 输出该源刮削到的全部字段内容（info 级）。刮削器在 scrape() 成功返回前调用。
     * 简介仅展示前 120 字符（去除换行），完整内容看数据库。
     */
    fun logResult(number: String, meta: MovieMetadata) {
        val log = LoggerFactory.getLogger(this::class.java)
        val plot = meta.plot?.replace(Regex("""\s+"""), " ")?.trim()
        log.info(
            "[{}] {} 刮削结果:\n  标题: {}\n  中文标题: {}\n  日期: {} | 时长: {}分\n  导演: {} | 片商: {} | 发行商: {} | 系列: {}\n  女优: [{}]\n  类别: [{}]\n  封面: {}\n  剧照: {} 张\n  简介: {}",
            id(), number,
            meta.title ?: "—", meta.titleZh ?: "—",
            meta.releaseDate ?: "—", meta.durationMin ?: "—",
            meta.director ?: "—", meta.maker ?: "—", meta.label ?: "—", meta.series ?: "—",
            meta.actresses.joinToString(" / ").ifEmpty { "—" },
            meta.genres.joinToString(" / ").ifEmpty { "—" },
            meta.coverUrl ?: "—",
            meta.previewUrls.size,
            plot?.take(120)?.ifBlank { null }?.plus("…") ?: "—",
        )
    }
}
