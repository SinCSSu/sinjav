package top.sincs.sinjav.scraper

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import top.sincs.sinjav.config.AppProperties
import java.util.Collections
import java.util.concurrent.Executors

/**
 * 刮削策略链（复刻 JavSP 流水线）：按番号类型选择对应刮削链（normal/fc2/cid/getchu/gyutto），
 * 链内所有数据源并发请求（HTTP 层仍受 request-interval-ms 全局限速约束）。
 * 每个刮削字段按链内顺序优先级从高到低取第一个非空值；列表字段（女优/类别/剧照）取并集。
 * 显式传入 sources 时忽略链配置（如多源对比的手动指定）。
 */
@Component
class ScraperChain(
    scrapers: List<Scraper>,
    private val props: AppProperties,
) {

    private val log = LoggerFactory.getLogger(ScraperChain::class.java)
    private val byId = scrapers.associateBy { it.id() }

    /** 按番号形态归类：FC2 系 → fc2；GETCHU/GYUTTO 前缀 → 专用链；纯数字 6-8 位 → cid；其余 → normal */
    fun chainTypeOf(number: String): ScrapeChainType = when {
        number.uppercase().startsWith("FC2") -> ScrapeChainType.FC2
        number.uppercase().startsWith("GETCHU-") -> ScrapeChainType.GETCHU
        number.uppercase().startsWith("GYUTTO-") -> ScrapeChainType.GYUTTO
        Regex("""^\d{6,8}$""").matches(number) -> ScrapeChainType.CID
        else -> ScrapeChainType.NORMAL
    }

    /** 该番号应使用的刮削链（过滤掉未注册的源 id；链内全部无效时回退 sourceOrder） */
    fun chainFor(number: String): List<String> {
        val type = chainTypeOf(number)
        val chain = props.chainFor(type).filter { byId.containsKey(it) }
        return chain.ifEmpty { props.sourceOrderList().filter { byId.containsKey(it) } }
    }

    @Throws(ScrapeException::class)
    fun scrape(number: String, sources: List<String>? = null): MovieMetadata =
        scrapeWithSource(number, sources).second

    /** 返回 (来源标识, 元数据)；多源成功时来源标识为 "merged" */
    @Throws(ScrapeException::class)
    fun scrapeWithSource(number: String, sources: List<String>? = null): Pair<String, MovieMetadata> {
        val order = (sources ?: chainFor(number)).filter { byId.containsKey(it) }
        if (order.isEmpty()) throw ScrapeException("无可用刮削源，可用源: ${byId.keys}")
        val results = scrapeAllSources(number, order)
        return if (results.size > 1) "merged" to merge(results)
        else results.first()
            .let { (id, meta) ->
                val candidates = listOfNotNull(meta.coverUrl?.let { CoverCandidate(it, id) })
                id to meta.copy(
                    coverSource = id, previewSource = id, coverCandidates = candidates,
                    previewCandidates = meta.previewUrls.map { CoverCandidate(it, id) },
                )
            }
    }

    /**
     * 并发抓取全部数据源（不合并），返回按刮削链顺序排序的全部成功结果。
     * 供多源对比刮削预览使用：用户逐字段选择来源后落库。
     */
    @Throws(ScrapeException::class)
    fun scrapeAllSources(number: String, order: List<String>? = null): List<Pair<String, MovieMetadata>> {
        val effectiveOrder = (order ?: chainFor(number)).filter { byId.containsKey(it) }
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
                        if (reason.contains("未找到")) {
                            // 未命中番号属预期行为（多源并发必有大量未命中），降噪到 DEBUG
                            log.debug("刮削源 {} 未命中 {}: {}", id, number, reason)
                        } else {
                            // 真实故障（403 风控/超时/解析崩溃）：单行 WARN，堆栈放 DEBUG
                            log.warn("刮削源 {} 失败 {}: {} ({})", id, number, reason, e::class.simpleName)
                            log.debug("刮削源 {} 失败堆栈", id, e)
                        }
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
        // 封面候选列表：各源封面按链序收集（复刻 JavSP covers），javdb 封面固定沉底（站点水印）
        val candidates = orderCoverCandidates(
            sortedResults.mapNotNull { (id, meta) -> meta.coverUrl?.let { CoverCandidate(it, id) } },
        )
        // 剧照逐张记录来源：合并后列表里混有多个源的图（如 javbus 的 dmm 图床 + javdb 的 jdbstatic），
        // 只保留单一 previewSource 会把图发到错误的出口通道（直连/过盾）。同 URL 取链序在前的源
        val previewCandidates = sortedResults
            .flatMap { (id, meta) -> meta.previewUrls.map { url -> CoverCandidate(url, id) } }
            .distinctBy { it.url }
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
            coverUrl = candidates.firstOrNull()?.url,
            coverSource = candidates.firstOrNull()?.source,
            previewUrls = previewCandidates.map { it.url },
            previewSource = previewCandidates.firstOrNull()?.source,
            previewCandidates = previewCandidates,
            coverCandidates = candidates,
            plot = pick { it.plot },
            actresses = sortedResults.flatMap { it.second.actresses }.distinct(),
            genres = sortedResults.flatMap { it.second.genres }.distinct(),
        )
    }

    companion object {
        /** 封面固定沉底的数据源（站点水印，复刻 JavSP use_javdb_cover: fallback 的固定版） */
        const val DEMOTED_COVER_SOURCE = "javdb"

        /**
         * 封面候选排序：chosen（用户多源对比明确选择的封面）置顶（不受沉底规则约束），
         * 其余候选按传入顺序、javdb 移到末尾；按 URL 去重。
         */
        fun orderCoverCandidates(
            chosen: CoverCandidate?,
            pool: List<CoverCandidate>,
        ): List<CoverCandidate> {
            val rest = pool.filter { it.url != chosen?.url }
            val orderedRest = rest.filterNot { it.source == DEMOTED_COVER_SOURCE } +
                rest.filter { it.source == DEMOTED_COVER_SOURCE }
            return (listOfNotNull(chosen) + orderedRest).distinctBy { it.url }
        }

        /** 多源合并场景：无用户选择，直接按链序整理（javdb 沉底） */
        private fun orderCoverCandidates(pool: List<CoverCandidate>): List<CoverCandidate> =
            orderCoverCandidates(null, pool)
    }

    /** 数据源标识（供配置校验） */
    fun knownSources(): Set<String> = byId.keys
}
