package top.sincs.sinjav.scraper

import org.jsoup.Jsoup
import org.springframework.stereotype.Component
import top.sincs.sinjav.config.AppProperties

/**
 * avwiki（avwiki.py 移植）：WordPress 文章路由，URL 直达无搜索页（https://av-wiki.net/{番号}）。
 * 字段集中在 dl.dltable（dt=字段名 / dd=值）；AV女優名 保留列表，其余取首项。
 */
@Component
class AvwikiScraper(
    private val http: HttpClientFactory,
    private val props: AppProperties,
) : Scraper {

    override fun id() = "avwiki"

    override fun scrape(number: String): MovieMetadata {
        val base = props.baseUrlFor("avwiki").trimEnd('/')
        val html = runCatching { http.get("$base/${ScraperParsers.urlEncode(number)}", source = id()) }
            .getOrElse { throw ScrapeException("avwiki 未找到番号 $number（${it.message}）") }
        val doc = Jsoup.parse(html, "$base/$number")
        val body = doc.selectFirst("section.article-body")
            ?: throw ScrapeException("avwiki 未找到番号 $number 的文章内容")

        // 封面：优先 srcset 中最大宽度那张，回退 src
        val coverImg = doc.selectFirst("header a.image-link-border img")
        val coverUrl = coverImg?.let { img ->
            val srcset = img.attr("srcset")
            if (srcset.isNotBlank()) {
                srcset.split(",").mapNotNull { candidate ->
                    val parts = candidate.trim().split(Regex("\\s+"))
                    val w = parts.getOrNull(1)?.removeSuffix("w")?.toIntOrNull() ?: 0
                    parts.firstOrNull()?.trim()?.takeIf { it.isNotBlank() }?.let { w to it }
                }.maxByOrNull { it.first }?.second
            } else img.attr("src").takeIf { it.isNotBlank() }
        }

        // dl.dltable → dt/dd 映射
        val dl = body.selectFirst("dl.dltable")
        val pairs = dl?.select("dt")?.mapNotNull { dt ->
            val dd = dt.nextElementSibling() ?: return@mapNotNull null
            val values = if (dd.select("a").isNotEmpty()) dd.select("a").map { it.text().trim() }
            else listOf(dd.text().trim())
            dt.text().trim() to values.filter { it.isNotBlank() }
        } ?: emptyList()
        fun firstOf(key: String): String? = pairs.firstOrNull { it.first == key }?.second?.firstOrNull()

        val title = ScraperParsers.blankToNull(
            body.selectFirst("div p")?.textNodes()?.firstOrNull()?.text()
                ?.replace("【$number】", "")?.trim(),
        ) ?: ScraperParsers.blankToNull(body.selectFirst("div p")?.text()?.replace("【$number】", "")?.trim())

        return MovieMetadata(
            number = number,
            title = title,
            maker = ScraperParsers.blankToNull(firstOf("メーカー")),
            series = ScraperParsers.blankToNull(firstOf("シリーズ")),
            releaseDate = ScraperParsers.parseDate(firstOf("配信開始日")),
            coverUrl = coverUrl,
            actresses = pairs.firstOrNull { it.first == "AV女優名" }?.second ?: emptyList(),
        ).also { logResult(number, it) }
    }
}
