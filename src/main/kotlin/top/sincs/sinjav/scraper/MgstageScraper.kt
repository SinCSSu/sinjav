package top.sincs.sinjav.scraper

import org.jsoup.Jsoup
import org.springframework.stereotype.Component
import top.sincs.sinjav.config.AppProperties

/**
 * MGStage（mgstage.py 移植）：官方站（需要日本 IP），adc=1 年龄认证 Cookie。
 * 女优名同时存在于 td 纯文本与 a 链接中，两处合并；简介段落保留 <br> 换行。
 */
@Component
class MgstageScraper(
    private val http: HttpClientFactory,
    private val props: AppProperties,
) : Scraper {

    override fun id() = "mgstage"

    override fun scrape(number: String): MovieMetadata {
        val base = props.baseUrlFor("mgstage").trimEnd('/')
        val html = runCatching {
            // Cookie 由 HttpClientFactory 统一解析：数据源独立配置 > 内置 adc=1 年龄验证
            http.get("$base/product/product_detail/${ScraperParsers.urlEncode(number)}/", source = id())
        }.getOrElse { e ->
            val msg = e.message ?: ""
            if (msg.contains("HTTP 403")) {
                // 403 是被站点风控/地域拦截，不是没这部片
                throw ScrapeException("mgstage 拒绝访问（403，反爬/地域限制，可尝试镜像 base-url 或代理）")
            }
            // 未命中会重定向到主页（200 但无详情容器）；其他错误原样抛出
            throw ScrapeException("mgstage 未找到番号 $number（$msg）")
        }
        val doc = Jsoup.parse(html, "$base/")
        val detail = doc.selectFirst("div.common_detail_cover")
            ?: throw ScrapeException("mgstage 未找到番号 $number")

        fun thValue(label: String): org.jsoup.nodes.Element? =
            doc.select("th").firstOrNull { it.text().startsWith(label) }?.nextElementSibling()

        // 女优：td 纯文本（无链接女优）+ td a（有链接女优）合并
        val actressTd = thValue("出演：")
        val actresses = buildList {
            actressTd?.ownText()?.trim()?.takeIf { it.isNotBlank() }?.let { add(it) }
            actressTd?.select("a")?.forEach { it.text().trim().takeIf { t -> t.isNotBlank() }?.let(::add) }
        }.distinct()

        // 简介：p 内保留 <br> 换行
        val plotEl = doc.selectFirst("dl#introduction dd p:not(.more)")
        val plot = plotEl?.let { p ->
            val sb = StringBuilder()
            p.childNodes().forEach { node ->
                when {
                    node is org.jsoup.nodes.TextNode -> sb.append(node.text())
                    node is org.jsoup.nodes.Element && node.tagName() == "br" -> sb.append('\n')
                    node is org.jsoup.nodes.Element -> sb.append(node.text())
                    else -> node.toString().trim().takeIf { it.isNotBlank() }?.let { sb.append(it) }
                }
            }
            ScraperParsers.blankToNull(sb.toString())
        }

        return MovieMetadata(
            number = number,
            title = ScraperParsers.blankToNull(detail.selectFirst("h1")?.text()),
            coverUrl = ScraperParsers.blankToNull(doc.selectFirst("a#EnlargeImage")?.attr("abs:href")),
            releaseDate = ScraperParsers.parseDate(thValue("配信開始日：")?.text()),
            durationMin = ScraperParsers.minutesFrom(thValue("収録時間：")?.text()),
            maker = ScraperParsers.blankToNull(thValue("メーカー：")?.selectFirst("a")?.text()),
            series = ScraperParsers.blankToNull(thValue("シリーズ：")?.selectFirst("a")?.text()),
            actresses = actresses,
            genres = thValue("ジャンル：")?.select("a")?.map { it.text().trim() }?.filter { it.isNotBlank() }
                ?: emptyList(),
            plot = plot,
            previewUrls = doc.select("a.sample_image").mapNotNull { ScraperParsers.blankToNull(it.attr("abs:href")) },
        ).also { logResult(number, it) }
    }
}
