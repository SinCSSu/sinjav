package top.sincs.sinjav.scraper

import org.jsoup.Jsoup
import org.springframework.stereotype.Component
import top.sincs.sinjav.config.AppProperties

/**
 * FANZA/DMM（fanza.py 移植，有适配）：age_check_done=1 Cookie。
 * JavSP 按 cid 直达（我们无 cid 概念）→ 适配为按番号走 DMM 站内搜索，取排序最优候选进详情。
 * 详情字段用表格式提取（td 文本 = 字段名 + 下一格为值）；地域受限（提示 not available in your region）时明确报错。
 */
@Component
class FanzaScraper(
    private val http: HttpClientFactory,
    private val props: AppProperties,
) : Scraper {

    override fun id() = "fanza"

    override fun scrape(number: String): MovieMetadata {
        val base = props.baseUrlFor("fanza").trimEnd('/')

        // 先试番号规整形式的 cid 直达（常见规律：IPX-177 → /cid=ipx00177/，仅对纯字母+3~5位数字番号生效）
        val cidGuess = number.lowercase().let { n ->
            Regex("""^([a-z]+)(\d{3,5})$""").find(n)?.let { m ->
                m.groupValues[1] + m.groupValues[2].padStart(5, '0')
            }
        }
        val candidates = buildList {
            cidGuess?.let { add("$base/digital/videoa/-/detail/=/cid=$it/") }
        }
        val detailUrl = candidates.firstOrNull { url ->
            runCatching {
                val html = http.get(url, source = id())
                regionCheck(html)
                Jsoup.parse(html, url).selectFirst("div.hreview h1") != null
            }.getOrDefault(false)
        } ?: searchDetailUrl(base, number)

        val html = http.get(detailUrl, source = id())
        regionCheck(html)
        val doc = Jsoup.parse(html, detailUrl)
        return parseVideoaPage(doc, number).also { logResult(number, it) }
    }

    /** 站内搜索取第一条结果（数字视频优先于实物/月额，与 JavSP 排序权重一致） */
    private fun searchDetailUrl(base: String, number: String): String {
        val html = runCatching {
            http.get("$base/search/?redirect=1&enc=UTF-8&category=&searchstr=${ScraperParsers.urlEncode(number)}&commit.x=0&commit.y=0", source = id())
        }.getOrElse { throw ScrapeException("fanza 未找到番号 $number（${it.message}）") }
        regionCheck(html)
        val doc = Jsoup.parse(html)
        val links = doc.select("ul#list li div p a").mapNotNull { ScraperParsers.blankToNull(it.attr("abs:href")) }
        val best = links.firstOrNull { "/digital/videoa/" in it }
            ?: links.firstOrNull { "/mono/" in it }
            ?: links.firstOrNull()
            ?: throw ScrapeException("fanza 未找到番号 $number")
        return best.substringBefore('?') + "/"
    }

    private fun regionCheck(html: String) {
        if (html.contains("not available in your region")) {
            throw ScrapeException("FANZA 限制当前地域访问（需日本 IP）")
        }
    }

    /** videoa 详情页：字段都在 <td>文字：</td><td>值</td> 的表格行里 */
    internal fun parseVideoaPage(doc: org.jsoup.nodes.Document, number: String): MovieMetadata {
        val title = ScraperParsers.blankToNull(doc.selectFirst("div.hreview h1")?.text())
            ?: throw ScrapeException("fanza 详情页解析失败：$number")

        fun tdValue(label: String): String? =
            doc.select("td").firstOrNull { it.text().startsWith(label) }?.nextElementSibling()?.text()?.trim()

        return MovieMetadata(
            number = number,
            title = title,
            coverUrl = ScraperParsers.blankToNull(doc.selectFirst("div#sample-video a")?.attr("abs:href")),
            releaseDate = ScraperParsers.parseDate(tdValue("配信開始日：")),
            durationMin = ScraperParsers.minutesFrom(tdValue("収録時間：")),
            director = ScraperParsers.blankToNull(tdValue("監督：")),
            maker = ScraperParsers.blankToNull(tdValue("メーカー：")),
            series = ScraperParsers.blankToNull(tdValue("シリーズ：")),
            actresses = doc.select("span#performer a").map { it.text().trim() }.filter { it.isNotBlank() },
            genres = doc.select("td").firstOrNull { it.text().startsWith("ジャンル：") }
                ?.nextElementSibling()?.select("a")
                ?.filter { it.attr("href").contains("keyword") || it.attr("href").contains("article=keyword") }
                ?.map { it.text().trim() }?.filter { it.isNotBlank() } ?: emptyList(),
            plot = ScraperParsers.blankToNull(
                doc.select("div.mg-b20").firstOrNull()?.textNodes()?.firstOrNull()?.text(),
            ),
            previewUrls = doc.select("a[name=sample-image] img").mapNotNull { ScraperParsers.blankToNull(it.attr("abs:src")) },
        )
    }
}
