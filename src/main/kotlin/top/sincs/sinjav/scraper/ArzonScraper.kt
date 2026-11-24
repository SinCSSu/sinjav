package top.sincs.sinjav.scraper

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.springframework.stereotype.Component
import top.sincs.sinjav.config.AppProperties
import java.time.LocalDate

/**
 * Arzon 基座（移植 JavSP arzon.py / arzon_iv.py）：
 * 每次刮削先访问年龄验证页拿会话 Cookie，再搜索（取第一条结果）→ 详情解析。
 * 详情页字段集中在一个 <table>（key=第一列，value=第二列），两个爬虫共用表格解析。
 */
abstract class ArzonScraperBase(
    protected val http: HttpClientFactory,
    protected val props: AppProperties,
) : Scraper {

    protected val base: String get() = props.baseUrlFor("arzon").trimEnd('/')

    /** 年龄验证（JavSP 用 http 的 agecheck URL 取会话 Cookie） */
    protected fun ageCookie(): String {
        val (_, _, cookie) = http.getDetailed(
            "http://www.arzon.jp/index.php?action=adult_customer_agecheck&agecheck=1",
            source = id(),
        )
        return cookie ?: ""
    }

    protected fun get(url: String, cookie: String): String =
        http.get(url, cookie = cookie, source = id())

    /** 详情页信息表格：key(第一列) → value(第二列纯文本，多行合并) */
    protected fun parseInfoTable(doc: Document, tableSelector: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        doc.select("$tableSelector tr").forEach { tr ->
            val tds = tr.select("td")
            if (tds.size >= 2) {
                map[tds[0].text().trim()] = tds[1].text().trim()
            }
        }
        return map
    }

    /** 表格公共字段：発売日 / 収録時間 / 監督 / タグ */
    protected fun applyCommonFields(info: Map<String, String>, builder: MovieMetadataBuilder) {
        builder.releaseDate = Regex("\\d{4}/\\d{2}/\\d{2}").find(info["発売日："] ?: "")?.value
            ?.replace('/', '-')?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        builder.durationMin = Regex("([\\d.]+)分").find(info["収録時間："] ?: "")?.groupValues?.get(1)?.toDoubleOrNull()?.toInt()
        builder.director = ScraperParsers.blankToNull(info["監督："])
        builder.genres = listOfNotNull(
            ScraperParsers.blankToNull(info["タグ："]),
            ScraperParsers.blankToNull(info["AVレーベル："]) ?: ScraperParsers.blankToNull(info["イメージレベル："]),
        )
    }
}

/** 借助可变构建器在两个爬虫间复用逐字段赋值逻辑 */
class MovieMetadataBuilder(val number: String) {
    var title: String? = null
    var plot: String? = null
    var coverUrl: String? = null
    var releaseDate: LocalDate? = null
    var director: String? = null
    var maker: String? = null
    var label: String? = null
    var series: String? = null
    var durationMin: Int? = null
    var previewUrls: MutableList<String> = mutableListOf()
    val actresses: MutableList<String> = mutableListOf()
    var genres: List<String> = emptyList()

    fun firstText(el: Element?, selector: String): String? =
        ScraperParsers.blankToNull(el?.selectFirst(selector)?.text())

    fun build(): MovieMetadata = MovieMetadata(
        number = number,
        title = title,
        releaseDate = releaseDate,
        director = director,
        maker = maker,
        label = label,
        series = series,
        durationMin = durationMin,
        coverUrl = coverUrl,
        previewUrls = previewUrls.toList(),
        plot = plot,
        actresses = actresses,
        genres = genres,
    )
}

/** Arzon 常规影片（arzon.py 移植） */
@Component
class ArzonScraper(http: HttpClientFactory, props: AppProperties) : ArzonScraperBase(http, props) {

    override fun id() = "arzon"

    override fun scrape(number: String): MovieMetadata {
        val cookie = ageCookie()
        val searchHtml = get("$base/itemlist.html?t=&m=all&s=&q=${ScraperParsers.urlEncode(number)}", cookie)
        // 带 baseUri：结果链接用 abs:href 解析（相对路径）
        val searchDoc = org.jsoup.Jsoup.parse(searchHtml, "$base/")
        val itemUrl = ScraperParsers.blankToNull(searchDoc.select("h2 a").firstOrNull()?.attr("abs:href"))
            ?: throw ScrapeException("Arzon 未找到番号 $number 的搜索结果")

        val doc = org.jsoup.Jsoup.parse(get(itemUrl, cookie), itemUrl)
        val b = MovieMetadataBuilder(number)
        b.title = ScraperParsers.blankToNull(
            doc.selectFirst("div.detail_title_new2 h1")?.textNodes()?.firstOrNull()?.text(),
        ) ?: ScraperParsers.blankToNull(doc.selectFirst("div.detail_title_new2 h1")?.text())
        b.coverUrl = doc.selectFirst("td[align=center] a img")?.attr("src")?.let { absHttps(it) }
        b.plot = doc.select("div.item_text").map { it.text().trim() }.firstOrNull { it.isNotBlank() }
        b.previewUrls += doc.select("div.detail_img img").mapNotNull { img ->
            absHttps(img.attr("src"))?.replace("m_", "")
        }

        val info = parseInfoTable(doc, "div.item_register table")
        b.actresses += (info["AV女優："] ?: "").split(Regex("[,/、]")).map { it.trim() }.filter { it.isNotBlank() }
        b.maker = ScraperParsers.blankToNull(info["AVメーカー："])
        b.series = ScraperParsers.blankToNull(info["シリーズ："])
        applyCommonFields(info, b)
        return b.build().also { logResult(number, it) }
    }

    private fun absHttps(src: String): String? =
        ScraperParsers.blankToNull(src)?.let { if (it.startsWith("//")) "https:$it" else it }
}

/** Arzon イメージビデオ（arzon_iv.py 移植：KIDM 等写真影像） */
@Component
class ArzonIvScraper(http: HttpClientFactory, props: AppProperties) : ArzonScraperBase(http, props) {

    override fun id() = "arzon_iv"

    override fun scrape(number: String): MovieMetadata {
        val cookie = ageCookie()
        val searchHtml = get("$base/imagelist.html?q=${ScraperParsers.urlEncode(number)}", cookie)
        // 带 baseUri：结果链接用 abs:href 解析（相对路径）
        val searchDoc = org.jsoup.Jsoup.parse(searchHtml, "$base/")
        val itemUrl = ScraperParsers.blankToNull(searchDoc.select("h2 a").firstOrNull()?.attr("abs:href"))
            ?: throw ScrapeException("ArzonIV 未找到番号 $number 的搜索结果")

        val doc = org.jsoup.Jsoup.parse(get(itemUrl, cookie), itemUrl)
        val b = MovieMetadataBuilder(number)
        b.title = ScraperParsers.blankToNull(doc.selectFirst("div.detail_title_new h1")?.text())
        b.coverUrl = doc.selectFirst("td[align=center] a img")?.attr("src")?.let { absHttps(it) }

        val info = parseInfoTable(doc, "div.item_register table")
        b.actresses += (info["タレント："] ?: "").split(Regex("[,/、]")).map { it.trim() }.filter { it.isNotBlank() }
        b.maker = ScraperParsers.blankToNull(info["イメージメーカー："])
        b.genres = listOfNotNull(ScraperParsers.blankToNull(info["タグ："]))
        applyCommonFields(info, b)
        return b.build().also { logResult(number, it) }
    }

    private fun absHttps(src: String): String? =
        ScraperParsers.blankToNull(src)?.let { if (it.startsWith("//")) "https:$it" else it }
}
