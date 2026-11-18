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

    // ---------- 页面选择器常量（2026-11 实测于 www.javbus.com，改版只需调整这里） ----------
    // 标题：<div class="container"><h3>番号 标题</h3>（h3 无 class）
    private val selTitle = "h3"
    // 封面：<div class="col-md-9 screencap"><a class="bigImage" href=...><img src=...>
    private val selCover = "a.bigImage img"
    // 信息面板：<div class="col-md-3 info"><p><span class="header">字段:</span> 值</p>...
    private val selInfoPanel = "div.col-md-3.info p"
    // 类别：信息面板内链接到 /genre/ 的 <a>（女优为 /star/，导演/厂商等各有路径，不会误中）
    private val selGenres = "div.col-md-3.info a[href*=/genre/]"
    // 女优：<div class="star-name"><a title=...>名字</a></div>
    private val selActresses = "div.star-name a"
    // 剧照：<div id="sample-waterfall">（无样本的影片该 div 不存在）
    private val selSamples = "div#sample-waterfall a"

    override fun scrape(number: String): MovieMetadata {
        val base = props.scraper.baseUrlJavbus.trimEnd('/')
        val html = http.get("$base/search/${urlEncode(number)}", referer = base)
        val searchDoc = Jsoup.parse(html)
        val detailUrl = searchDoc.select("a.movie-box").firstOrNull()?.attr("href")
            ?: throw ScrapeException("JavBus 未找到番号 $number 的搜索结果")

        // 传入 baseUri，使相对路径的封面/剧照链接可以通过 absUrl() 解析为绝对地址
        val doc = Jsoup.parse(http.get(detailUrl, referer = base), detailUrl)
        return parseDetail(doc, number)
    }

    internal fun parseDetail(doc: Document, number: String): MovieMetadata {
        val info = doc.select(selInfoPanel).map { it.text() }
        val releaseDate = info.firstOrNull { it.startsWith("發行日期") || it.startsWith("发行日期") }
            ?.substringAfter(':')?.trim()?.let { runCatching { LocalDate.parse(it, DateTimeFormatter.ofPattern("yyyy-MM-dd")) }.getOrNull() }
        val durationMin = info.firstOrNull { it.startsWith("長度") || it.startsWith("长度") }
            ?.substringAfter(':')?.trim()?.trimEnd('分', '鐘', '钟')?.trim()?.toIntOrNull()
        val director = info.firstOrNull { it.startsWith("導演") || it.startsWith("导演") }
            ?.substringAfter(':')?.trim()?.ifBlank { null }
        // 注意：页面实际字段为「製作商」「發行商」；「發行」前缀会误匹配「發行日期」
        val maker = info.firstOrNull { it.startsWith("製作商") || it.startsWith("制作商") }
            ?.substringAfter(':')?.trim()?.ifBlank { null }
        val label = info.firstOrNull { it.startsWith("發行商") || it.startsWith("发行商") }
            ?.substringAfter(':')?.trim()?.ifBlank { null }
        val series = info.firstOrNull { it.startsWith("系列") }
            ?.substringAfter(':')?.trim()?.ifBlank { null }

        val coverUrl = doc.selectFirst(selCover)?.absUrl("src")?.takeIf { it.isNotBlank() }
        val previews = doc.select(selSamples).mapNotNull { it.absUrl("href").takeIf { u -> u.isNotBlank() } }
        val actresses = doc.select(selActresses).map { it.text().trim() }.filter { it.isNotBlank() }.distinct()
        val title = doc.selectFirst(selTitle)?.text()?.trim()
            // h3 文本以番号开头（如 "ABP-123 标题"），剥离番号前缀
            ?.replaceFirst(Regex("^" + Regex.escape(number) + "\\s*", RegexOption.IGNORE_CASE), "")
            ?.ifBlank { null }

        return MovieMetadata(
            number = number,
            title = title,
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
