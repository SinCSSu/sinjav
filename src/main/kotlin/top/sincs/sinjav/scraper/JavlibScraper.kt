package top.sincs.sinjav.scraper

import org.jsoup.Jsoup
import org.springframework.stereotype.Component
import top.sincs.sinjav.config.AppProperties

/**
 * JavLibrary（javlib.py 移植）：老牌档案库（常被墙/换域名，需镜像或过盾代理）。
 * /cn/vl_searchbyid.php?keyword=番号 → 唯一命中会直接重定向到详情页；
 * 搜索列表时按番号精确匹配 div.video 条目（>2 个候选且无法剔除蓝光版时报重复）。
 */
@Component
class JavlibScraper(
    private val http: HttpClientFactory,
    private val props: AppProperties,
) : Scraper {

    override fun id() = "javlib"

    override fun scrape(number: String): MovieMetadata {
        val base = props.baseUrlFor("javlib").trimEnd('/')
        val html = runCatching {
            http.get("$base/cn/vl_searchbyid.php?keyword=${ScraperParsers.urlEncode(number)}", source = id())
        }.getOrElse { throw ScrapeException("javlib 请求失败：$number（${it.message}）") }
        // 必须带 baseUri：搜索结果页里的 href 是相对路径，searchHit 用 abs:href 解析；
        // 缺 baseUri 时 abs:href 为空 → 第二次请求抛「URI with undefined scheme」
        val doc = Jsoup.parse(html, "$base/")

        // 详情页特征：存在 #video_info；否则是搜索结果页
        val detailDoc = if (doc.selectFirst("#video_info") != null) doc
        else searchHit(doc, number)?.takeIf { it.isNotBlank() }?.let {
            Jsoup.parse(http.get(it, source = id()), it)
        } ?: throw ScrapeException("javlib 未找到番号 $number")

        return parseDetail(detailDoc, number).also { logResult(number, it) }
    }

    /** 搜索结果列表中按番号精确匹配；2 个候选时剔除蓝光版（ブルーレイディスク） */
    private fun searchHit(doc: org.jsoup.nodes.Document, number: String): String? {
        val hits = doc.select("div.video[id] a").filter { a ->
            a.selectFirst("div.id")?.text()?.trim()?.uppercase() == number.uppercase()
        }
        return when {
            hits.isEmpty() -> null
            hits.size == 1 -> hits[0].attr("abs:href")
            else -> hits.filter { !it.text().contains("ブルーレイディスク") }
                .takeIf { it.size == 1 }?.first()?.attr("abs:href")
                ?: throw ScrapeException("javlib 番号 $number 存在多个版本，无法唯一确定")
        }
    }

    internal fun parseDetail(doc: org.jsoup.nodes.Document, number: String): MovieMetadata {
        val info = doc.selectFirst("#video_info") ?: throw ScrapeException("javlib 详情页解析失败：$number")
        fun cellText(id: String): String? =
            ScraperParsers.blankToNull(info.selectFirst("#$id td.text")?.text())
        fun spanText(cls: String): String? =
            ScraperParsers.blankToNull(info.selectFirst("span.$cls a")?.text())

        val coverRaw = ScraperParsers.blankToNull(doc.selectFirst("#video_jacket_img")?.attr("abs:src"))
        return MovieMetadata(
            number = number,
            title = ScraperParsers.blankToNull(
                doc.selectFirst("#rightcolumn h3 a")?.text()?.replace(number, "")?.trim(),
            ),
            coverUrl = coverRaw?.let { if (it.startsWith("//")) "https:$it" else it },
            releaseDate = ScraperParsers.parseDate(cellText("video_date")),
            durationMin = ScraperParsers.minutesFrom(cellText("video_length")),
            director = spanText("director"),
            maker = spanText("maker"),
            // JavLibrary 的 label 即发行商（与我们 label 语义一致）
            label = spanText("label"),
            actresses = info.select("span.star a").map { it.text().trim() }.filter { it.isNotBlank() },
            genres = info.select("span.genre a").map { it.text().trim() }.filter { it.isNotBlank() },
        )
    }
}
