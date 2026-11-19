package top.sincs.sinjav.scraper

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.springframework.stereotype.Component
import top.sincs.sinjav.config.AppProperties
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * JavDB 刮削器（部分信息需登录 Cookie）。
 * 页面结构集中在本类中，站点改版只需调整选择器常量。
 */
@Component
class JavDbScraper(
    private val http: HttpClientFactory,
    private val props: AppProperties,
) : Scraper {

    override fun id() = "javdb"

    // ---------- 页面选择器常量（2026-11 实测，改版只需调整这里） ----------
    // 搜索结果条目：新版 div.item > a.box；旧版直接 a.item（两者兼容）
    private val selResultLink = "div.movie-list a.box, div.movie-list a.item"
    // 标题：strong.current-title 为原题，strong.origin-title 为其他语言标题（存在时）
    private val selTitle = "h2.title strong.current-title"
    private val selTitleZh = "h2.title strong.origin-title"
    private val selTitleBlock = "h2.title"
    // 封面：详情页左侧封面图
    private val selCover = "div.column-video-cover img"
    // 信息面板：nav.movie-panel-info 下的 panel-block（<strong>标签:</strong><span class="value">值</span>）；
    // 兼容旧版 nav.panel-block 直挂结构
    private val selInfoPanel = "nav.movie-panel-info .panel-block, nav.panel-block"
    // 类别：信息面板内链接到 /tags? 的 <a>（简繁 locale 均适用）
    private val selGenres = "nav.movie-panel-info a[href*=/tags?]"
    // 女优：演员面板内标记为 actor-female 的链接（排除同面板的男性演员/制作人员）
    private val selActresses = "nav.movie-panel-info a.actor-female"
    // 剧照：新版 div.tile-images.preview-images；旧版 div.screenshot-panel（两者兼容）
    private val selPreviews = "div.tile-images.preview-images a.tile-item, div.screenshot-panel a.tile-item"
    // 简介：可能带「展开」按钮文字，截取前 2000 字符兜底
    private val selPlot = "div.video-description"

    override fun scrape(number: String): MovieMetadata {
        val base = props.scraper.baseUrlJavdb.trimEnd('/')
        val cookie = props.scraper.javdbCookie
        val html = http.get("$base/search?q=${urlEncode(number)}&f=all", cookie = cookie, source = id())
        val searchDoc = Jsoup.parse(html)
        val detailHref = searchDoc.select(selResultLink).firstOrNull()?.attr("href")
            ?: throw ScrapeException("JavDB 未找到番号 $number 的搜索结果")
        val detailUrl = if (detailHref.startsWith("http")) detailHref else base + detailHref

        val doc = Jsoup.parse(http.get(detailUrl, referer = base, cookie = cookie, source = id()))
        return parseDetail(doc, number).also { logResult(number, it) }
    }

    internal fun parseDetail(doc: Document, number: String): MovieMetadata {
        // 信息面板按「标签 -> 值」读取；标签随 locale 有简繁差异（日期/日期、时长/時長 等），匹配时都列出来
        fun info(vararg labels: String): String? {
            val blocks = doc.select(selInfoPanel)
            for (block in blocks) {
                val label = block.selectFirst("strong")?.text()?.trimEnd(':') ?: continue
                if (labels.any { label.startsWith(it) }) {
                    return block.selectFirst(".value")?.text()?.trim()?.ifBlank { null }
                }
            }
            return null
        }

        val releaseDate = info("日期", "發行時間", "发行时间")
            ?.let { Regex("""\d{4}-\d{2}-\d{2}""").find(it)?.value }
            ?.let { runCatching { LocalDate.parse(it, DateTimeFormatter.ofPattern("yyyy-MM-dd")) }.getOrNull() }
        val durationMin = info("时长", "時長", "长度", "長度")
            ?.let { Regex("""\d+""").find(it)?.value?.toIntOrNull() }
        val director = info("导演", "導演")
        val maker = info("片商", "制作商", "製作商", "制作")
        val label = info("发行商", "發行商")

        val coverUrl = doc.selectFirst(selCover)?.absUrl("src")?.takeIf { it.isNotBlank() }
        val previews = doc.select(selPreviews).mapNotNull { it.absUrl("href").takeIf { u -> u.isNotBlank() } }
        val actresses = doc.select(selActresses).map { it.text().trim() }.filter { it.isNotBlank() }.distinct()

        // 标题：优先取 current-title（原题，不含番号前缀）；缺失时回退整个标题块并剥离番号前缀
        val title = doc.selectFirst(selTitle)?.text()?.trim()?.ifBlank { null }
            ?: doc.selectFirst(selTitleBlock)?.text()?.trim()
                ?.replaceFirst(Regex("^" + Regex.escape(number) + "\\s*", RegexOption.IGNORE_CASE), "")
                ?.ifBlank { null }
        val titleZh = doc.selectFirst(selTitleZh)?.text()?.trim()?.ifBlank { null }

        return MovieMetadata(
            number = number,
            title = title,
            titleZh = titleZh,
            releaseDate = releaseDate,
            director = director,
            maker = maker,
            label = label,
            series = info("系列"),
            durationMin = durationMin,
            coverUrl = coverUrl,
            previewUrls = previews,
            plot = doc.selectFirst(selPlot)?.text()?.trim()?.take(2000)?.ifBlank { null },
            actresses = actresses,
            genres = doc.select(selGenres).map { it.text().trim() }.filter { it.isNotBlank() }.distinct(),
        )
    }

    private fun urlEncode(s: String): String =
        java.net.URLEncoder.encode(s, Charsets.UTF_8)
}
