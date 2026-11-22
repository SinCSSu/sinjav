package top.sincs.sinjav.scraper

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.springframework.stereotype.Component
import top.sincs.sinjav.config.AppProperties
import java.time.LocalDate

/**
 * MissAV 刮削器（missav.ai）。站点与封面 CDN fourhoi.com 均有 Cloudflare 盾，
 * 需在设置中把 missav 加入过盾数据源（HttpClientFactory 也会按域名兜底路由）。
 * 直接按番号访问详情页 https://missav.ai/{number小写}，无需搜索页。
 * 页面结构集中在本类中，站点改版只需调整选择器常量。
 */
@Component
class MissAvScraper(
    private val http: HttpClientFactory,
    private val props: AppProperties,
) : Scraper {

    override fun id() = "missav"

    // ---------- 页面选择器常量（2026-11 实测于 missav.ai，改版只需调整这里） ----------
    // 信息块：<div class="text-secondary"><span>标签:</span> 值（time / span.font-medium / a 列表）
    private val selInfoBlocks = "div.text-secondary"
    // 翻译标题：h1 = "番号 翻译标题 - 女优"
    private val selH1 = "h1"
    // 封面：og:image（fourhoi.com CDN，如 https://fourhoi.com/pfes-107/cover-n.jpg）
    private val selCoverMeta = "meta[property=og:image]"
    // 简介：og:description（为机器翻译长文）
    private val selPlotMeta = "meta[property=og:description]"

    override fun scrape(number: String): MovieMetadata {
        val base = props.baseUrlFor("missav").trimEnd('/')
        val doc = Jsoup.parse(http.get("$base/${number.lowercase()}", referer = base, source = id()), base)
        return parseDetail(doc, number).also { logResult(number, it) }
    }

    internal fun parseDetail(doc: Document, number: String): MovieMetadata {
        /** 标签前缀匹配信息块（标签随 locale 有简繁差异，匹配时都列出来） */
        fun infoBlock(vararg labels: String): Element? =
            doc.select(selInfoBlocks).firstOrNull { block ->
                val label = block.selectFirst("span")?.text()?.trimEnd(':') ?: ""
                labels.any { label.startsWith(it) }
            }

        fun infoValue(vararg labels: String): String? =
            infoBlock(*labels)?.selectFirst(".font-medium, time")?.text()?.trim()?.ifBlank { null }

        // 「番號」块是详情页的标志（不存在的番号返回无信息壳页）
        infoValue("番號", "番号") ?: throw ScrapeException("MissAV 未找到番号 $number 的详情页")

        val releaseDate = infoValue("發行日期", "发行日期")
            ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        // 时长：og:video:duration 为秒，向上取整为分钟
        val durationMin = doc.selectFirst("meta[property=og:video:duration]")?.attr("content")
            ?.toIntOrNull()?.let { Math.ceil(it / 60.0).toInt() }
        val director = infoValue("導演", "导演")
        val maker = infoValue("發行商", "发行商", "製作商", "制作商")
        val series = infoValue("系列")

        val actresses = infoBlock("女優", "女优")?.select("a")
            ?.map { it.text().trim() }?.filter { it.isNotBlank() }?.distinct().orEmpty()
        val genres = infoBlock("類型", "类型", "类别")?.select("a")
            ?.map { it.text().trim() }?.filter { it.isNotBlank() }?.distinct().orEmpty()

        val coverUrl = doc.selectFirst(selCoverMeta)?.attr("content")?.takeIf { it.isNotBlank() }
        // og:description 有两类广告：
        // 1) 真实简介尾部拼接的 DMM 活动广告（以长横线「-----」分隔）→ 截断
        // 2) 影片无简介时整段站点宣传语兜底（FC2 页常见，可能重复拼接）→ 整段清除，清完为空则不存简介
        val plot = doc.selectFirst(selPlotMeta)?.attr("content")?.trim()
            ?.replace(Regex("""\s*-{10,}[\s\S]*$"""), "")
            ?.replace(
                Regex("""\s*免費高清日本\s*AV\s*在線看[\s\S]*?免費加入會員後可任意收藏影片供日後觀賞。?"""),
                "",
            )
            ?.trimStart('\\')
            ?.trim()
            ?.take(2000)
            ?.ifBlank { null }

        val title = infoValue("標題", "标题")
        // titleZh：h1（机器翻译标题）剥离番号前缀与「 - 女优」后缀
        val titleZh = doc.selectFirst(selH1)?.text()?.trim()
            ?.replaceFirst(Regex("^" + Regex.escape(number) + "\\s*", RegexOption.IGNORE_CASE), "")
            ?.let { h1 ->
                val first = actresses.firstOrNull()
                val suffix = " - $first"
                if (first != null && h1.endsWith(suffix)) h1.removeSuffix(suffix) else h1
            }
            ?.ifBlank { null }

        return MovieMetadata(
            number = number,
            title = title,
            titleZh = titleZh,
            releaseDate = releaseDate,
            director = director,
            maker = maker,
            label = null,
            series = series,
            durationMin = durationMin,
            coverUrl = coverUrl,
            previewUrls = emptyList(),
            plot = plot,
            actresses = actresses,
            genres = genres,
        )
    }
}
