package top.sincs.sinjav.scraper

import org.springframework.stereotype.Component
import top.sincs.sinjav.config.AppProperties
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

/**
 * AiRav（airav.py 移植）：纯 JSON API。抓繁体（zh-TW）。
 * 1 跳 barcode 查询；count=0 且为 \d{6}[-_]\d{2,3} 无码番号时走列表搜索兜底（翻页聚合后匹配 barcode）。
 * 马赛克破坏版/无码流出版过滤：命中关键词的字段置空（避免刮到盗版衍生版本）。
 */
@Component
class AiravScraper(
    private val http: HttpClientFactory,
    private val props: AppProperties,
) : Scraper {

    override fun id() = "airav"

    private val mapper = ObjectMapper()

    override fun scrape(number: String): MovieMetadata {
        val base = props.baseUrlFor("airav").trimEnd('/')
        val result = runCatching { queryBarcode(base, number) }.getOrElse { e ->
            val msg = e.message ?: ""
            if (msg.contains("HTTP 403")) {
                // 403 是被站点风控拦截，不是没这部片
                throw ScrapeException("airav 拒绝访问（403，反爬/地域限制，可尝试镜像 base-url 或代理）")
            }
            // 兜底：列表搜索（仅无码番号格式），找到匹配 barcode 后回查
            if (Regex("""\d{6}[-_]\d{2,3}""").matches(number)) {
                val barcode = searchBarcode(base, number)
                    ?: throw ScrapeException("airav 未找到番号 $number（$msg）")
                queryBarcode(base, barcode)
            } else throw ScrapeException("airav 未找到番号 $number（$msg）")
        }

        val title = unescape(text(result, "name"))
            ?.takeUnless { it.contains("馬賽克破壞版") || it.contains("馬賽克破解版") || it.contains("無碼流出版") }
        val plot = unescape(text(result, "description"))
            ?.takeUnless { it.contains("馬賽克破壞版") || it.contains("馬賽克破解版") || it.contains("無碼流出版") }
        val genres = children(result.path("tags")).map { it.path("name").asString("") }
            .filter { it.isNotBlank() && !it.contains("破壞版") && !it.contains("流出版") }

        return MovieMetadata(
            number = number,
            title = title,
            coverUrl = text(result, "img_url"),
            plot = plot,
            releaseDate = ScraperParsers.parseDate(text(result, "publish_date")),
            previewUrls = children(result.path("images")).map { it.asString("") }.filter { it.isNotBlank() },
            actresses = children(result.path("actors")).map { it.path("name").asString("") }.filter { it.isNotBlank() },
            genres = genres,
            maker = children(result.path("factories")).firstOrNull()?.path("name")?.asString("")?.ifBlank { null },
        ).also { logResult(number, it) }
    }

    private fun children(node: JsonNode): List<JsonNode> = buildList { node.forEach { add(it) } }

    private fun text(node: JsonNode, field: String): String? =
        node.path(field).asString("").ifBlank { null }

    private fun queryBarcode(base: String, id: String): JsonNode {
        val body = http.get("$base/api/video/barcode/${ScraperParsers.urlEncode(id)}?lng=zh-TW", source = id())
        val root = mapper.readTree(body)
        if (root.path("count").asInt(0) <= 0) throw ScrapeException("barcode 查询无结果")
        return children(root.path("result")).firstOrNull() ?: throw ScrapeException("barcode 查询无结果")
    }

    /** 列表搜索翻页聚合，返回匹配的 barcode（无匹配返回 null） */
    private fun searchBarcode(base: String, id: String): String? {
        val needle = id.replace('-', '_')
        val all = mutableListOf<JsonNode>()
        for (page in 0 until 10) {
            val body = http.get("$base/api/video/list?lang=zh-TW&lng=zh-TW&search=${ScraperParsers.urlEncode(id)}&page=$page", source = id())
            val result = mapper.readTree(body).path("result")
            if (!result.isArray || result.size() == 0) break
            all += children(result)
        }
        return all.mapNotNull { n ->
            val barcode = n.path("barcode").asString("")
            if (barcode.replace('-', '_').contains(needle)) barcode else null
        }.firstOrNull()
    }

    private fun unescape(s: String?): String? = s?.replace("&amp;", "&")?.replace("&quot;", "\"")
        ?.replace("&#39;", "'")?.replace("&lt;", "<")?.replace("&gt;", ">")?.trim()
}
