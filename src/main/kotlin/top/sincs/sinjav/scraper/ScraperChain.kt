package top.sincs.sinjav.scraper

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import top.sincs.sinjav.config.AppProperties

/**
 * 刮削策略链：
 * - fallback：按 source-order 依次尝试，返回第一个成功源
 * - merge：收集所有成功源，字段合并（顺序靠前的源优先）
 */
@Component
class ScraperChain(
    scrapers: List<Scraper>,
    private val props: AppProperties,
) {

    private val log = LoggerFactory.getLogger(ScraperChain::class.java)
    private val byId = scrapers.associateBy { it.id() }

    @Throws(ScrapeException::class)
    fun scrape(number: String, sources: List<String>? = null, strategy: String? = null): MovieMetadata =
        scrapeWithSource(number, sources, strategy).second

    /** 返回 (来源标识, 元数据) */
    @Throws(ScrapeException::class)
    fun scrapeWithSource(number: String, sources: List<String>? = null, strategy: String? = null): Pair<String, MovieMetadata> {
        val order = (sources ?: props.sourceOrderList()).filter { byId.containsKey(it) }
        if (order.isEmpty()) throw ScrapeException("无可用刮削源，可用源: ${byId.keys}")
        val mode = strategy ?: props.scraper.strategy

        val results = mutableListOf<Pair<String, MovieMetadata>>() // (sourceId, metadata)
        val errors = mutableListOf<String>()
        for (id in order) {
            try {
                results += id to byId.getValue(id).scrape(number)
                if (mode != "merge") break
            } catch (e: Exception) {
                log.warn("刮削源 {} 失败 {}: {}", id, number, e.message)
                errors += "${id}: ${e.message}"
            }
        }
        if (results.isEmpty()) throw ScrapeException("全部刮削源失败（${errors.joinToString("; ")}）")
        return if (mode == "merge" && results.size > 1) "merged" to merge(results, order)
        else results.first()
    }

    /** 字段合并：按 source-order 中靠前的源优先，非空字段不覆盖 */
    private fun merge(results: List<Pair<String, MovieMetadata>>, order: List<String>): MovieMetadata {
        val sorted = results.sortedBy { order.indexOf(it.first) }.map { it.second }
        fun <T> pick(sel: (MovieMetadata) -> T?): T? = sorted.firstNotNullOfOrNull(sel)
        return MovieMetadata(
            number = sorted.first().number,
            title = pick { it.title },
            titleZh = pick { it.titleZh },
            releaseDate = pick { it.releaseDate },
            director = pick { it.director },
            maker = pick { it.maker },
            label = pick { it.label },
            series = pick { it.series },
            durationMin = pick { it.durationMin },
            coverUrl = pick { it.coverUrl },
            previewUrls = sorted.flatMap { it.previewUrls }.distinct(),
            plot = pick { it.plot },
            actresses = sorted.flatMap { it.actresses }.distinct(),
            genres = sorted.flatMap { it.genres }.distinct(),
        )
    }

    /** 数据源标识（供配置校验） */
    fun knownSources(): Set<String> = byId.keys
}
