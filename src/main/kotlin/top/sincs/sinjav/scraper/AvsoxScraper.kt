package top.sincs.sinjav.scraper

import org.jsoup.Jsoup
import org.springframework.stereotype.Component
import top.sincs.sinjav.config.AppProperties

/**
 * Avsox（avsox.py 移植）：javbus 风格站点的番号档案库（老片/FC2 补充源）。
 * 搜索页 movie-box 列表按番号精确匹配后进详情；FC2 片把「系列」字段的拍摄者写入 maker。
 */
@Component
class AvsoxScraper(
    private val http: HttpClientFactory,
    private val props: AppProperties,
) : Scraper {

    override fun id() = "avsox"

    override fun scrape(number: String): MovieMetadata {
        // 配置的镜像地址需以 / 结尾（与 JavSP 一致的处理）；FC2 统一为 FC2-PPV- 前缀再搜索
        val base = props.baseUrlFor("avsox").trimEnd('/') + '/'
        val normalized = number.uppercase().replace("FC2-PPV-", "FC2-")
        val searchId = normalized.replace("FC2-", "FC2-PPV-")

        val searchDoc = Jsoup.parse(http.get("${base}tw/search/${ScraperParsers.urlEncode(searchId)}", source = id()))
        val boxes = searchDoc.select("a.movie-box")
        val hit = boxes.firstOrNull { box ->
            val id = box.select("div.photo-info span date").firstOrNull()?.text()?.trim()
            id?.let { it.equals(normalized, true) || it.equals(searchId, true) } == true
        } ?: throw ScrapeException("avsox 未找到番号 $number")

        // 详情统一走 cn 语言路径
        val detailUrl = hit.attr("abs:href").replace("/tw/", "/cn/")
        val doc = Jsoup.parse(http.get(detailUrl, source = id()), detailUrl)
        return parseDetail(doc, number).also { logResult(number, it) }
    }

    internal fun parseDetail(doc: org.jsoup.nodes.Document, number: String): MovieMetadata {
        val infoPanel = doc.select("div.col-md-3.info p")
        fun tailOf(label: String): String? {
            val p = infoPanel.firstOrNull { el -> el.selectFirst("span")?.text()?.startsWith(label) == true }
                ?: infoPanel.firstOrNull { it.text().startsWith(label) }
                ?: return null
            val span = p.selectFirst("span")
            return span?.nextSibling()?.toString()?.trim()?.takeIf { it.isNotBlank() } ?: p.text().substringAfter(':').trim()
        }

        val title = ScraperParsers.blankToNull(
            doc.selectFirst("h3")?.textNodes()?.firstOrNull()?.text()?.replace(number, "")?.trim(),
        ) ?: ScraperParsers.blankToNull(doc.selectFirst("h3")?.text()?.replace(number, "")?.trim())
        val producer = infoPanel.firstOrNull { it.text().startsWith("制作商:") }
            ?.select("a")?.firstOrNull()?.text()?.trim()?.takeIf { it.isNotBlank() }
        val serial = infoPanel.firstOrNull { it.text().startsWith("系列:") }
            ?.select("a")?.firstOrNull()?.text()?.trim()?.takeIf { it.isNotBlank() }

        // FC2 片：系列字段是拍摄者，落到 maker
        val isFc2 = number.uppercase().startsWith("FC2")
        return MovieMetadata(
            number = number,
            title = title,
            releaseDate = ScraperParsers.parseDate(tailOf("发行时间:")),
            durationMin = ScraperParsers.minutesFrom(tailOf("长度:")),
            maker = if (isFc2) serial ?: producer else producer,
            series = if (isFc2) null else serial,
            coverUrl = ScraperParsers.blankToNull(doc.selectFirst("a.bigImage")?.attr("abs:href")),
            actresses = doc.select("a.avatar-box span").map { it.text().trim() }.filter { it.isNotBlank() },
            genres = doc.select("span.genre a").map { it.text().trim() }.filter { it.isNotBlank() },
        )
    }
}
