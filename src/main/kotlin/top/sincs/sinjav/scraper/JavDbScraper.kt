package top.sincs.sinjav.scraper

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.springframework.stereotype.Component
import top.sincs.sinjav.config.AppProperties
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * JavDB 刮削器（部分信息需登录 Cookie）。
 */
@Component
class JavDbScraper(
    private val http: HttpClientFactory,
    private val props: AppProperties,
) : Scraper {

    override fun id() = "javdb"

    // ---------- 页面选择器常量 ----------
    private val selResultLink = "div.movie-list a.item"
    private val selTitle = "h2.title"
    private val selCover = "div.column-video-cover img"
    private val selInfoPanel = "nav.panel-block"
    private val selTags = "div.current-tag-cloud a"
    private val selActresses = "div.container.panel-block a.avatar-box span.name"
    private val selPreviews = "div.screenshot-panel a.tile-item"

    override fun scrape(number: String): MovieMetadata {
        val base = props.scraper.baseUrlJavdb.trimEnd('/')
        val cookie = props.scraper.javdbCookie
        val html = http.get("$base/search?q=${urlEncode(number)}&f=all", cookie = cookie)
        val searchDoc = Jsoup.parse(html)
        val detailHref = searchDoc.select(selResultLink).firstOrNull()?.attr("href")
            ?: throw ScrapeException("JavDB 未找到番号 $number 的搜索结果")
        val detailUrl = if (detailHref.startsWith("http")) detailHref else base + detailHref

        val doc = Jsoup.parse(http.get(detailUrl, referer = base, cookie = cookie))
        return parseDetail(doc, number)
    }

    private fun parseDetail(doc: Document, number: String): MovieMetadata {
        val info = doc.select(selInfoPanel).map { it.text() }
        val releaseDate = info.firstOrNull { it.startsWith("发行时间") }
            ?.substringAfterLast(' ')?.trim()
            ?.let { runCatching { LocalDate.parse(it, DateTimeFormatter.ofPattern("yyyy-MM-dd")) }.getOrNull() }
        val durationMin = info.firstOrNull { it.startsWith("长度") }
            ?.substringAfterLast(' ')?.trim()?.trimEnd('分', '钟')?.trim()?.toIntOrNull()
        val director = info.firstOrNull { it.startsWith("导演") }?.substringAfterLast(' ')?.trim()?.ifBlank { null }
        val maker = info.firstOrNull { it.startsWith("制作") }?.substringAfterLast(' ')?.trim()?.ifBlank { null }
        val label = info.firstOrNull { it.startsWith("发行商") }?.substringAfterLast(' ')?.trim()?.ifBlank { null }
        val series = info.firstOrNull { it.startsWith("系列") }?.substringAfterLast(' ')?.trim()?.ifBlank { null }

        val coverUrl = doc.selectFirst(selCover)?.absUrl("src")?.takeIf { it.isNotBlank() }
        val previews = doc.select(selPreviews).mapNotNull { it.absUrl("href").takeIf { u -> u.isNotBlank() } }
        val actresses = doc.select(selActresses).map { it.text().trim() }.filter { it.isNotBlank() }

        return MovieMetadata(
            number = number,
            title = doc.selectFirst(selTitle)?.text()?.trim(),
            titleZh = null,
            releaseDate = releaseDate,
            director = director,
            maker = maker,
            label = label,
            series = series,
            durationMin = durationMin,
            coverUrl = coverUrl,
            previewUrls = previews,
            actresses = actresses,
            genres = doc.select(selTags).map { it.text().trim() }.filter { it.isNotBlank() },
        )
    }

    private fun urlEncode(s: String): String =
        java.net.URLEncoder.encode(s, Charsets.UTF_8)
}
