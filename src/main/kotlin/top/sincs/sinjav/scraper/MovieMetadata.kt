package top.sincs.sinjav.scraper

import java.time.LocalDate

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
    val plot: String? = null,
    val actresses: List<String> = emptyList(),
    val genres: List<String> = emptyList(),
)

class ScrapeException(message: String, cause: Throwable? = null) : Exception(message, cause)

interface Scraper {
    /** 数据源标识：javbus / javdb */
    fun id(): String

    /** 按番号刮削元数据，失败抛 ScrapeException */
    @Throws(ScrapeException::class)
    fun scrape(number: String): MovieMetadata
}
