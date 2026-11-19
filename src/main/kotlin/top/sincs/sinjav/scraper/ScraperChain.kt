package top.sincs.sinjav.scraper

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import top.sincs.sinjav.config.AppProperties
import java.util.Collections
import java.util.concurrent.Executors

/**
 * 刮削策略链：对单个影片，所有数据源并发请求（HTTP 层仍受 request-interval-ms 全局限速约束）。
 * 每个刮削字段按 source-order 优先级从高到低取第一个非空值；列表字段（女优/类别/剧照）取并集。
 */
@Component
class ScraperChain(
    scrapers: List<Scraper>,
    private val props: AppProperties,
) {

    private val log = LoggerFactory.getLogger(ScraperChain::class.java)
    private val byId = scrapers.associateBy { it.id() }

    @Throws(ScrapeException::class)
    fun scrape(number: String, sources: List<String>? = null): MovieMetadata =
        scrapeWithSource(number, sources).second

    /** 返回 (来源标识, 元数据)；多源成功时来源标识为 "merged" */
    @Throws(ScrapeException::class)
    fun scrapeWithSource(number: String, sources: List<String>? = null): Pair<String, MovieMetadata> {
        val order = (sources ?: props.sourceOrderList()).filter { byId.containsKey(it) }
        if (order.isEmpty()) throw ScrapeException("无可用刮削源，可用源: ${byId.keys}")
        val results = scrapeAllSources(number, order)
        return if (results.size > 1) "merged" to merge(results)
        else results.first()
            .let { (id, meta) -> id to meta.copy(coverSource = id, previewSource = id) }
    }

    /**
     * 并发抓取全部数据源（不合并），返回按 source-order 排序的全部成功结果。
     * 供多源对比刮削预览使用：用户逐字段选择来源后落库。
     */
    @Throws(ScrapeException::class)
    fun scrapeAllSources(number: String, order: List<String>? = null): List<Pair<String, MovieMetadata>> {
        val effectiveOrder = (order ?: props.sourceOrderList()).filter { byId.containsKey(it) }
        if (effectiveOrder.isEmpty()) throw ScrapeException("无可用刮削源，可用源: ${byId.keys}")

        val results = Collections.synchronizedList(mutableListOf<Pair<String, MovieMetadata>>()) // (sourceId, metadata)
        val errors = Collections.synchronizedList(mutableListOf<String>())
        val executor = Executors.newFixedThreadPool(effectiveOrder.size.coerceAtLeast(1))
        try {
            effectiveOrder.map { id ->
                executor.submit {
                    try {
                        results += id to byId.getValue(id).scrape(number)
                    } catch (e: Exception) {
                        // 部分异常 message 为 null（如无消息的 NPE），带上异常类名便于诊断
                        val reason = e.message?.takeIf { it.isNotBlank() } ?: "${e::class.simpleName}（无错误信息）"
                        log.warn("刮削源 {} 失败 {}: {}", id, number, reason, e)
                        errors += "${id}: $reason"
                    }
                }
            }.forEach { it.get() } // 等待全部数据源完成
        } finally {
            executor.shutdown()
        }
        if (results.isEmpty()) throw ScrapeException("全部刮削源失败（${errors.joinToString("; ")}）")
        return results.sortedBy { effectiveOrder.indexOf(it.first) }
    }

    /** 将 scrapeAllSources 的结果（已按 source-order 排序）合并为单份元数据：每字段取第一个非空值，列表字段取并集 */
    fun mergeSources(results: List<Pair<String, MovieMetadata>>): MovieMetadata = merge(results)

    /** 字段合并：入参已按 source-order 排序，非空字段不覆盖；图片字段记录实际来源数据源 */
    private fun merge(sortedResults: List<Pair<String, MovieMetadata>>): MovieMetadata {
        val metas = sortedResults.map { it.second }
        fun <T> pick(sel: (MovieMetadata) -> T?): T? = metas.firstNotNullOfOrNull(sel)
        return MovieMetadata(
            number = metas.first().number,
            title = pick { it.title },
            titleZh = pick { it.titleZh },
            releaseDate = pick { it.releaseDate },
            director = pick { it.director },
            maker = pick { it.maker },
            label = pick { it.label },
            series = pick { it.series },
            durationMin = pick { it.durationMin },
            coverUrl = pick { it.coverUrl },
            coverSource = sortedResults.firstOrNull { it.second.coverUrl != null }?.first,
            previewUrls = sortedResults.flatMap { it.second.previewUrls }.distinct(),
            previewSource = sortedResults.firstOrNull { it.second.previewUrls.isNotEmpty() }?.first,
            plot = pick { it.plot },
            actresses = sortedResults.flatMap { it.second.actresses }.distinct(),
            genres = sortedResults.flatMap { it.second.genres }.distinct(),
        )
    }

    /** 数据源标识（供配置校验） */
    fun knownSources(): Set<String> = byId.keys
}
