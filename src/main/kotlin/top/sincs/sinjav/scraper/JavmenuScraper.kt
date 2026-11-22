package top.sincs.sinjav.scraper

import org.jsoup.Jsoup
import org.springframework.stereotype.Component
import top.sincs.sinjav.config.AppProperties

/**
 * JavMenu（javmenu.py 移植）：URL 直达 {域名}/{番号}；未命中会重定向回主页（容器节点缺失）。
 * 卡片字段用「span 文本 = 字段名」+ 下一兄弟节点取值；封面优先 video[data-poster]。
 */
@Component
class JavmenuScraper(
    private val http: HttpClientFactory,
    private val props: AppProperties,
) : Scraper {

    override fun id() = "javmenu"

    override fun scrape(number: String): MovieMetadata {
        val base = props.baseUrlFor("javmenu").trimEnd('/')
        val html = runCatching { http.get("$base/${ScraperParsers.urlEncode(number)}", source = id()) }
            .getOrElse { throw ScrapeException("javmenu 未找到番号 $number（${it.message}）") }
        val doc = Jsoup.parse(html, "$base/$number")
        val card = doc.selectFirst("div.card-body")
            ?: throw ScrapeException("javmenu 未找到番号 $number（可能被重定向回主页）")

        fun siblingOf(prefix: String): org.jsoup.nodes.Element? =
            card.select("div span").firstOrNull { it.ownText().startsWith(prefix) }?.nextElementSibling()
        fun spanSiblingText(prefix: String): String? =
            card.select("div span").firstOrNull { it.ownText().startsWith(prefix) }?.nextSibling()?.toString()
                ?.trim()?.takeIf { it.isNotBlank() }

        // 标题去掉站点广告后缀
        val rawTitle = ScraperParsers.blankToNull(
            doc.selectFirst("div.col-12.mb-3 h1 strong")?.text(),
        )
        val title = rawTitle?.replace("  | JAV目錄大全 | 每日更新", "")
            ?.replace(" 免費在線看", "")?.replace(" 免費AV在線看", "")
            ?.replace(number, "")?.trim()?.ifBlank { null }

        val coverUrl = ScraperParsers.blankToNull(doc.selectFirst("div.single-video video[data-poster]")?.attr("data-poster"))
            ?: ScraperParsers.blankToNull(doc.selectFirst("img.lazy.rounded")?.attr("data-src"))
        val previews = doc.select("a[data-fancybox=gallery]").mapNotNull { ScraperParsers.blankToNull(it.attr("abs:href")) }

        return MovieMetadata(
            number = number,
            title = title,
            coverUrl = coverUrl ?: previews.firstOrNull(),
            previewUrls = previews,
            releaseDate = ScraperParsers.parseDate(spanSiblingText("日期:")),
            durationMin = ScraperParsers.minutesFrom(spanSiblingText("時長:")),
            maker = ScraperParsers.blankToNull(siblingOf("製作:")?.selectFirst("a span")?.text()),
            actresses = siblingOf("女優:")?.select("a")?.map { it.text().trim() }?.filter { it.isNotBlank() }
                ?: emptyList(),
            genres = doc.select("a.genre").map { it.text().trim() }.filter { it.isNotBlank() },
        ).also { logResult(number, it) }
    }
}
