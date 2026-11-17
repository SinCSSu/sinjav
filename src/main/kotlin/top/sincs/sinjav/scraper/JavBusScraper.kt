package top.sincs.sinjav.scraper

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.springframework.stereotype.Component
import top.sincs.sinjav.config.AppProperties
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * JavBus 刮削器。页面结构集中在本类中，站点改版只需调整选择器常量。
 */
@Component
class JavBusScraper(
    private val http: HttpClientFactory,
    private val props: AppProperties,
) : Scraper {

    override fun id() = "javbus"

    // ---------- 页面选择器常量 ----------
    private val selTitle = "h3.title"
    private val selCover = "a.big-image img"
    private val selInfoPanel = "div#movie-info p"
    private val selGenres = "div.tag-container:containsOwn(類別:) span.genre-item a"
    private val selActresses = "div#avatar-modal div.avatar-box span.name"
    private val selSamples = "div#sample-waterfall a"

    override fun scrape(number: String): MovieMetadata {
        val base = props.scraper.baseUrlJavbus.trimEnd('/')
        val html = http.get("$base/search/${urlEncode(number)}", referer = base)
        val searchDoc = Jsoup.parse(html)
        val detailUrl = searchDoc.select("a.movie-box").firstOrNull()?.attr("href")
            ?: throw ScrapeException("JavBus 未找到番号 $number 的搜索结果")

        val doc = Jsoup.parse(http.get(detailUrl, referer = base))
        return parseDetail(doc, number)
    }

    private fun parseDetail(doc: Document, number: String): MovieMetadata {
        val info = doc.select(selInfoPanel).map { it.text() }
        val releaseDate = info.firstOrNull { it.startsWith("發行日期") || it.startsWith("发行日期") }
            ?.substringAfter(':')?.trim()?.let { runCatching { LocalDate.parse(it, DateTimeFormatter.ofPattern("yyyy-MM-dd")) }.getOrNull() }
        val durationMin = info.firstOrNull { it.startsWith("長度") || it.startsWith("长度") }
            ?.substringAfter(':')?.trim()?.trimEnd('分', '鐘', '钟')?.trim()?.toIntOrNull()
        val director = info.firstOrNull { it.startsWith("導演") || it.startsWith("导演") }
            ?.substringAfter(':')?.trim()?.ifBlank { null }
        val maker = info.firstOrNull { it.startsWith("製作") || it.startsWith("制作") }
            ?.substringAfter(':')?.trim()?.ifBlank { null }
        val label = info.firstOrNull { it.startsWith("發行") || it.startsWith("发行") }
            ?.substringAfter(':')?.trim()?.ifBlank { null }
        val series = info.firstOrNull { it.startsWith("系列") }
            ?.substringAfter(':')?.trim()?.ifBlank { null }

        val coverUrl = doc.selectFirst(selCover)?.absUrl("src")?.takeIf { it.isNotBlank() }
        val previews = doc.select(selSamples).mapNotNull { it.absUrl("href").takeIf { u -> u.isNotBlank() } }
        val actresses = doc.select(selActresses).map { it.text().trim() }.filter { it.isNotBlank() }

        return MovieMetadata(
            number = number,
            title = doc.selectFirst(selTitle)?.text()?.trim(),
            releaseDate = releaseDate,
            director = director,
            maker = maker,
            label = label,
            series = series,
            durationMin = durationMin,
            coverUrl = coverUrl,
            previewUrls = previews,
            actresses = actresses,
            genres = doc.select(selGenres).map { it.text().trim() }.filter { it.isNotBlank() },
        )
    }

    private fun urlEncode(s: String): String =
        java.net.URLEncoder.encode(s, Charsets.UTF_8)
}
