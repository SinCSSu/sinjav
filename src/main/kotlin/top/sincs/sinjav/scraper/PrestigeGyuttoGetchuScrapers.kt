package top.sincs.sinjav.scraper

import org.jsoup.Jsoup
import org.springframework.stereotype.Component
import top.sincs.sinjav.config.AppProperties

/**
 * Prestige 官方站（prestige.py 移植）：__age_auth__=true Cookie（不带会被重定向且拿不到数据）。
 * 500 = 无此影片（非网络问题）；发售日藏在链接参数 ?date= 中；レーベル 映射到系列。
 */
@Component
class PrestigeScraper(
    private val http: HttpClientFactory,
    private val props: AppProperties,
) : Scraper {

    override fun id() = "prestige"

    override fun scrape(number: String): MovieMetadata {
        val base = props.baseUrlFor("prestige").trimEnd('/')
        val html = runCatching {
            // Cookie 由 HttpClientFactory 统一解析：数据源独立配置 > 内置 __age_auth__=true 年龄验证
            http.get("$base/goods/goods_detail.php?sku=${ScraperParsers.urlEncode(number)}", source = id())
        }.getOrElse { throw ScrapeException("prestige 未找到番号 $number（HTTP 500 表示无此影片）") }
        // 带 baseUri：封面/剧照用 abs:src 解析，缺 baseUri 会得到空串（封面剧照静默丢失）
        val doc = Jsoup.parse(html, "$base/")
        val container = doc.selectFirst("section.px-4.mb-4")
            ?: throw ScrapeException("prestige 未找到番号 $number")

        fun pNext(label: String): org.jsoup.nodes.Element? =
            doc.select("p").firstOrNull { it.ownText().startsWith(label) }?.nextElementSibling()

        // 标题在 h1 内 span 的尾部文本节点
        val h1 = container.selectFirst("h1")
        val title = h1?.textNodes()?.map { it.text().trim() }?.lastOrNull { it.isNotBlank() }
            ?: h1?.ownText()?.trim()?.takeIf { it.isNotBlank() }

        val dateHref = pNext("発売日：")?.selectFirst("a")?.attr("href") ?: ""
        val previews = doc.select("h2:containsOwn(サンプル画像) ~ div picture source img, h2:containsOwn(サンプル画像) ~ div picture img")
            .mapNotNull { ScraperParsers.blankToNull(it.attr("abs:src")?.substringBefore('?')) }

        return MovieMetadata(
            number = number,
            title = title,
            coverUrl = ScraperParsers.blankToNull(
                container.selectFirst("div.c-ratio-image img")?.attr("abs:src")?.substringBefore('?'),
            ),
            releaseDate = ScraperParsers.parseDate(dateHref.substringAfter("?date=", "").takeIf { it.isNotBlank() }),
            durationMin = ScraperParsers.minutesFrom(
                doc.select("p").firstOrNull { it.ownText().startsWith("収録時間：") }?.nextElementSibling()?.text(),
            ),
            maker = ScraperParsers.blankToNull(pNext("メーカー：")?.selectFirst("a")?.text()),
            series = ScraperParsers.blankToNull(pNext("レーベル：")?.selectFirst("a")?.text()),
            actresses = pNext("出演者：")?.select("p a")?.map { it.text().trim().replace(" ", "") }
                ?.filter { it.isNotBlank() } ?: emptyList(),
            genres = pNext("ジャンル：")?.select("a")?.map { it.text().trim() }?.filter { it.isNotBlank() }
                ?: emptyList(),
            plot = ScraperParsers.blankToNull(
                doc.select("h2").firstOrNull { it.ownText() == "商品紹介" }?.nextElementSibling()?.selectFirst("p")?.text(),
            ),
            previewUrls = previews,
        ).also { logResult(number, it) }
    }
}

/**
 * Gyutto（gyutto.py 移植）：同人/写真平台（GYUTTO- 前缀番号），**EUC-JP 编码**（UTF-8 会乱码）。
 * a.highslide 图片列表：第一张为封面，其余为剧照。
 */
@Component
class GyuttoScraper(
    private val http: HttpClientFactory,
    private val props: AppProperties,
) : Scraper {

    override fun id() = "gyutto"

    override fun scrape(number: String): MovieMetadata {
        if (!number.uppercase().startsWith("GYUTTO-")) throw ScrapeException("gyutto 仅支持 GYUTTO- 前缀番号")
        val id = number.uppercase().removePrefix("GYUTTO-")
        val base = props.baseUrlFor("gyutto").trimEnd('/')
        val bytes = runCatching { http.download("$base/i/item$id?select_uaflag=1", source = id()) }
            .getOrElse { throw ScrapeException("gyutto 未找到番号 $number（${it.message}）") }
        val doc = Jsoup.parse(String(bytes, java.nio.charset.Charset.forName("EUC-JP")), "$base/i/item$id.html")

        val images = doc.select("a.highslide img").mapNotNull { ScraperParsers.blankToNull(it.attr("abs:src")) }
        val infoRows = doc.select("dl.BasicInfo").select("dt")
        fun ddOf(label: String): org.jsoup.nodes.Element? =
            infoRows.firstOrNull { it.ownText().startsWith(label) }?.nextElementSibling()

        return MovieMetadata(
            number = number,
            title = ScraperParsers.blankToNull(doc.selectFirst("h1")?.text()),
            coverUrl = images.firstOrNull(),
            previewUrls = images.drop(1),
            maker = ddOf("サークル")?.select("a")?.map { it.text().trim() }?.joinToString("")
                ?.takeIf { it.isNotBlank() },
            genres = ddOf("ジャンル")?.select("a")?.map { it.text().trim() }?.filter { it.isNotBlank() }
                ?: emptyList(),
            releaseDate = ScraperParsers.parseDate(ddOf("配信開始日")?.text()),
            plot = ScraperParsers.blankToNull(doc.selectFirst("div.unit_DetailLead p")?.text()),
        ).also { logResult(number, it) }
    }
}

/**
 * DL.Getchu（dl_getchu.py 移植）：G Equo 平台（GETCHU- 前缀番号），**EUC-JP 编码**。
 * 数据表为购物车 form 内的第 2/3 张 table；简介取「作品内容」中 ※ 注释行之前的内容。
 */
@Component
class DlGetchuScraper(
    private val http: HttpClientFactory,
    private val props: AppProperties,
) : Scraper {

    override fun id() = "dl_getchu"

    override fun scrape(number: String): MovieMetadata {
        if (!number.uppercase().startsWith("GETCHU-")) throw ScrapeException("dl_getchu 仅支持 GETCHU- 前缀番号")
        val id = number.uppercase().removePrefix("GETCHU-")
        val base = props.baseUrlFor("dl_getchu").trimEnd('/')
        val bytes = runCatching { http.download("$base/i/item$id", source = id()) }
            .getOrElse { throw ScrapeException("dl_getchu 未找到番号 $number（${it.message}）") }
        val doc = Jsoup.parse(String(bytes, java.nio.charset.Charset.forName("EUC-JP")), "$base/i/item$id.html")

        val cartForm = doc.selectFirst("form[action*=cart]") ?: throw ScrapeException("dl_getchu 未找到番号 $number")
        val tables = cartForm.select("table")

        // 标题：table[2] 各行最后一个非空文本
        val title = tables.getOrNull(1)?.select("tr td div")
            ?.map { it.text().trim() }?.lastOrNull { it.isNotBlank() }

        // table[3]：两单元格行 → key/value 映射
        val info = mutableMapOf<String, List<String>>()
        tables.getOrNull(2)?.select("table tr")?.forEach { tr ->
            val tds = tr.select("td")
            if (tds.size == 2) {
                val key = ScraperParsers.blankToNull(tds[0].selectFirst("td.bluetext")?.text() ?: tds[0].text()) ?: return@forEach
                val values = if (tds[1].select("a").isNotEmpty()) tds[1].select("a").map { it.text().trim() }
                else listOf(tds[1].text().trim())
                info[key] = values.filter { it.isNotBlank() }
            }
        }

        // 简介：作品内容值中「※」注释行之前的部分
        val plot = info["作品内容"]?.takeWhile { !it.startsWith("※") }?.joinToString("\n")?.trim()?.ifBlank { null }
        val previews = doc.select("img[src*=${id}_]").mapNotNull { ScraperParsers.blankToNull(it.attr("abs:src")) }

        return MovieMetadata(
            number = number,
            title = title,
            coverUrl = ScraperParsers.blankToNull(doc.selectFirst("img[src*=${id}top.jpg]")?.attr("abs:src")),
            previewUrls = previews,
            maker = info["サークル"]?.firstOrNull(),
            actresses = (info["作者"] ?: emptyList()).map { it.trim() }.filter { it.isNotBlank() },
            genres = info["趣向"] ?: emptyList(),
            releaseDate = ScraperParsers.parseDate(info["配信開始日"]?.firstOrNull()),
            plot = plot,
            durationMin = ScraperParsers.minutesFrom(info["画像数&ページ数"]?.joinToString(" ")),
        ).also { logResult(number, it) }
    }
}
