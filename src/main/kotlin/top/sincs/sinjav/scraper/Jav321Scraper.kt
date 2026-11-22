package top.sincs.sinjav.scraper

import org.jsoup.Jsoup
import org.springframework.stereotype.Component
import top.sincs.sinjav.config.AppProperties

/**
 * Jav321（jav321.py 移植）：唯一 POST 数据源 —— POST /search（form: sn=番号），搜索与详情同响应。
 * 女优名取头像链接内文本；封面取预览图列表首项；onerror 属性用于过滤占位图。
 */
@Component
class Jav321Scraper(
    private val http: HttpClientFactory,
    private val props: AppProperties,
) : Scraper {

    override fun id() = "jav321"

    override fun scrape(number: String): MovieMetadata {
        val base = props.baseUrlFor("jav321").trimEnd('/')
        val html = runCatching { http.postForm("$base/search", mapOf("sn" to number), source = id()) }
            .getOrElse { throw ScrapeException("jav321 请求失败：$number（${it.message}）") }
        val doc = Jsoup.parse(html, base)

        // dropdown-menu 第一项即详情页地址；仍停留在 search 表示未命中
        val pageUrl = doc.select("ul.dropdown-menu li a").firstOrNull()?.attr("abs:href")
        if (pageUrl == null || pageUrl.substringBefore('?').trimEnd('/').endsWith("search")) {
            throw ScrapeException("jav321 未找到番号 $number")
        }

        val info = doc.selectFirst("div.col-md-9")
        fun bValue(label: String): String? =
            doc.select("b").firstOrNull { it.text() == label }?.nextSibling()?.toString()
                ?.trim()?.removePrefix(": ")?.takeIf { it.isNotBlank() }

        // 预览图：优先正文列表，回退带 onerror 的缩略图行；URL 8 位后双斜杠修正
        val rawPics = doc.select("div.col-xs-12.col-md-12 p a img.img-responsive").map { it.attr("abs:src") }
            .ifEmpty { doc.select("div div div.col-md-3 img[onerror].img-responsive").map { it.attr("abs:src") } }
        val pics = rawPics.map { if (it.length > 8) it.take(8) + it.drop(8).replace("//", "/") else it }
            .filter { it.isNotBlank() }

        return MovieMetadata(
            number = number,
            title = ScraperParsers.blankToNull(doc.selectFirst("div.panel-heading h3")?.text()),
            coverUrl = pics.firstOrNull(),
            previewUrls = pics.drop(1),
            releaseDate = ScraperParsers.parseDate(bValue("配信開始日")),
            durationMin = ScraperParsers.minutesFrom(bValue("収録時間")),
            maker = info?.select("a[href*=/company/]")?.firstOrNull()?.text()?.trim()?.takeIf { it.isNotBlank() },
            series = info?.select("a[href*=/series/]")?.firstOrNull()?.text()?.trim()?.takeIf { it.isNotBlank() },
            actresses = doc.select("div.thumbnail a[href*=/star/] img").mapNotNull { img ->
                ScraperParsers.blankToNull(img.nextSibling()?.toString())
                    ?: ScraperParsers.blankToNull((img.parent() as? org.jsoup.nodes.Element)?.text())
            },
            genres = doc.select("a[href*=/genre/]").map { it.text().trim() }.filter { it.isNotBlank() },
            plot = ScraperParsers.blankToNull(
                doc.select("div.panel-body div.row div.col-md-12").firstOrNull()?.textNodes()?.firstOrNull()?.text(),
            ),
        ).also { logResult(number, it) }
    }
}
