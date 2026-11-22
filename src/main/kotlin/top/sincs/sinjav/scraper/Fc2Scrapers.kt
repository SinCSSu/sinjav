package top.sincs.sinjav.scraper

import org.jsoup.Jsoup
import org.springframework.stereotype.Component
import top.sincs.sinjav.config.AppProperties
import java.nio.file.Files
import java.nio.file.Path

/**
 * FC2 官方站（fc2.py 移植）：adult.contents.fc2.com/article/{id}/。
 * FC2 标题反爬把文字拆成多个节点 → 取 h3 全文本；封面优先取第一张预览图（主图只有 220x220）。
 * 跳转到 id.fc2.com 表示要求登录（日本 IP），视为被拦截。
 */
@Component
class Fc2Scraper(
    private val http: HttpClientFactory,
    private val props: AppProperties,
) : Scraper {

    override fun id() = "fc2"

    override fun scrape(number: String): MovieMetadata {
        if (!number.uppercase().startsWith("FC2")) throw ScrapeException("fc2 仅支持 FC2 番号")
        val fc2Id = number.uppercase().replace("FC2-PPV-", "").replace("FC2-", "")
        val base = props.baseUrlFor("fc2").trimEnd('/')

        val (html, finalUri, _) = http.getDetailed("$base/article/$fc2Id/", source = id())
        if (finalUri.host?.startsWith("id.fc2.com") == true) {
            throw ScrapeException("FC2 要求登录（日本 IP），被拦截：$number")
        }
        val doc = Jsoup.parse(html, finalUri.toString())
        val container = doc.selectFirst("div.items_article_left")
            ?: throw ScrapeException("FC2 未找到番号 $number")

        val previews = doc.select("ul[data-feed=sample-images] li a")
            .mapNotNull { ScraperParsers.blankToNull(it.attr("abs:href")) }
        val coverUrl = previews.firstOrNull()
            ?: ScraperParsers.blankToNull(doc.selectFirst("div.items_article_MainitemThumb span img")?.attr("abs:src"))
        val releaseDate = doc.selectFirst("div.items_article_Releasedate p")?.text()?.trim()
            ?.let { if (it.length >= 10) it.takeLast(10).replace('/', '-') else null }
            ?.let { ScraperParsers.parseDate(it) }

        return MovieMetadata(
            number = number,
            title = ScraperParsers.blankToNull(doc.selectFirst("div.items_article_headerInfo h3")?.text()),
            coverUrl = coverUrl,
            previewUrls = previews,
            durationMin = ScraperParsers.minutesFrom(
                doc.select("p.items_article_info").map { it.text() }.firstOrNull(),
            ),
            maker = ScraperParsers.blankToNull(
                doc.select("li").firstOrNull { it.text().trim().startsWith("by ") }?.selectFirst("a")?.text(),
            ),
            genres = doc.select("a.tag.tagTag").map { it.text().trim() }.filter { it.isNotBlank() },
            releaseDate = releaseDate,
        ).also { logResult(number, it) }
    }
}

/**
 * FC2PPVDB（fc2ppvdb.py 移植）：fc2ppvdb.com/articles/{id}，日文界面档案站。
 * 独有「モザイク」字段可判定有无码（無/有），販売者 → label。
 */
@Component
class Fc2PpvdbScraper(
    private val http: HttpClientFactory,
    private val props: AppProperties,
) : Scraper {

    override fun id() = "fc2ppvdb"

    override fun scrape(number: String): MovieMetadata {
        if (!number.uppercase().startsWith("FC2")) throw ScrapeException("fc2ppvdb 仅支持 FC2 番号")
        val fc2Id = number.uppercase().replace("FC2-PPV-", "").replace("FC2-", "")
        val base = props.baseUrlFor("fc2ppvdb").trimEnd('/')

        val html = runCatching { http.get("$base/articles/$fc2Id", source = id()) }
            .getOrElse { throw ScrapeException("fc2ppvdb 未找到番号 $number（${it.message}）") }
        val doc = Jsoup.parse(html, "$base/articles/$fc2Id")

        fun field(prefix: String): org.jsoup.nodes.Element? =
            doc.select("div").firstOrNull { it.ownText().startsWith(prefix) }

        val previews = emptyList<String>() // 该站无独立预览图列表
        return MovieMetadata(
            number = number,
            title = ScraperParsers.blankToNull(doc.selectFirst("h2 a")?.text()),
            coverUrl = ScraperParsers.blankToNull(doc.selectFirst("img[alt=$fc2Id]")?.attr("abs:src")),
            durationMin = ScraperParsers.minutesFrom(field("収録時間：")?.selectFirst("span")?.text()),
            actresses = field("女優：")?.select("span a")?.map { it.text().trim() }?.filter { it.isNotBlank() } ?: emptyList(),
            genres = field("タグ：")?.select("span a")?.map { it.text().trim() }?.filter { it.isNotBlank() } ?: emptyList(),
            releaseDate = ScraperParsers.parseDate(field("販売日：")?.select("span")?.firstOrNull()?.text()),
            label = ScraperParsers.blankToNull(field("販売者：")?.select("span a")?.firstOrNull()?.text()),
            previewUrls = previews,
        ).also { logResult(number, it) }
    }
}

/**
 * fc2fan（fc2fan.py 移植）：FC2 社区档案镜像。原站已关停：
 * 配置 fc2fanLocalPath 本地 HTML 目录（{番号}.html）时读本地，否则尝试远程镜像（大概率不可用）。
 */
@Component
class Fc2fanScraper(
    private val http: HttpClientFactory,
    private val props: AppProperties,
) : Scraper {

    override fun id() = "fc2fan"

    override fun scrape(number: String): MovieMetadata {
        if (!number.uppercase().startsWith("FC2")) throw ScrapeException("fc2fan 仅支持 FC2 番号")
        val doc = localDoc(number) ?: remoteDoc(number)
        val container = doc.selectFirst("div.col-sm-8")
            ?: throw ScrapeException("fc2fan 未找到番号 $number 的内容")

        fun strong(label: String): org.jsoup.nodes.Element? =
            container.select("h5 strong").firstOrNull { it.text().contains(label) }

        val previews = doc.select("ul.slides li img").mapNotNull { ScraperParsers.blankToNull(it.attr("abs:src")) }
        return MovieMetadata(
            number = number,
            title = ScraperParsers.blankToNull(container.selectFirst("h3")?.text()),
            coverUrl = previews.firstOrNull(),
            previewUrls = previews,
            maker = strong("卖家信息")?.nextSibling()?.toString()?.trim()?.takeIf { it.isNotBlank() },
            actresses = strong("女优名字")?.parent()?.select("a")?.map { it.text().trim() }?.filter { it.isNotBlank() }
                ?: emptyList(),
            genres = strong("影片标签")?.parent()?.select("a")?.map { it.text().trim() }?.filter { it.isNotBlank() }
                ?: emptyList(),
        ).also { logResult(number, it) }
    }

    private fun localDoc(number: String): org.jsoup.nodes.Document? {
        val dir = props.scraper.fc2fanLocalPath.trim()
        if (dir.isEmpty() || !Files.isDirectory(Path.of(dir))) return null
        val file = Path.of(dir, "$number.html")
        if (!Files.isRegularFile(file)) throw ScrapeException("fc2fan 本地镜像无 $number")
        return Jsoup.parse(Files.readString(file))
    }

    private fun remoteDoc(number: String): org.jsoup.nodes.Document {
        val base = props.baseUrlFor("fc2fan").trimEnd('/')
        val html = runCatching { http.get("$base/html/$number.html", source = id()) }
            .getOrElse { throw ScrapeException("fc2fan 站点不可用且未配置本地镜像：$number（${it.message}）") }
        if (html.isBlank()) throw ScrapeException("fc2fan 站点不可用：$number")
        return Jsoup.parse(html, "$base/html/$number.html")
    }
}
