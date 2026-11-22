package top.sincs.sinjav.scraper

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import top.sincs.sinjav.config.AppProperties
import top.sincs.sinjav.domain.JobRepository
import top.sincs.sinjav.domain.MovieRepository
import top.sincs.sinjav.scan.NumberRecognizer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** 数据源与合并策略由全局设置控制（设置页配置），不作为单次任务参数 */
data class ScrapeParams(
    val numbers: List<String>? = null,
)

@Service
class ScrapeService(
    private val chain: ScraperChain,
    private val movies: MovieRepository,
    private val jobs: JobRepository,
    private val recognizer: NumberRecognizer,
    private val imageStore: ImageStore,
    private val translation: TranslationService,
    private val props: AppProperties,
    private val jobControl: top.sincs.sinjav.job.JobControl,
) {

    private val log = LoggerFactory.getLogger(ScrapeService::class.java)

    /** 画质/尺寸类标签（高清、4K、1080P 等，简繁+英文写法），刮削时过滤不入库 */
    private val qualityTagRegex = Regex(
        """(?i)(4\s*k|8\s*k|2\s*k|\d{3,4}\s*p|fhd|uhd|高清|超清|藍光|蓝光|高畫質|高画質|畫質|画质)""",
    )

    /**
     * 执行刮削（同步）。numbers 为空时刮削全部 pending/failed 番号（可重试）。
     * 失败的番号记录原因，再次触发即可重试。
     *
     * 标题/简介翻译：标题译成中文后作为主标题存入 title（原标题存 title_orig）；
     * 简介存「中文简介\n\n原简介」；翻译失败则保留原文，不阻断刮削。
     */
    fun execute(jobId: Long, params: ScrapeParams) {
        val numbers = params.numbers?.takeIf { it.isNotEmpty() }?.mapNotNull { NumberRecognizer.normalize(it) }
            ?: movies.numbersNeedingScrape()

        log.info("刮削开始 jobId={} 番号数={} 并发={} 翻译源={}", jobId, numbers.size, props.scraper.concurrency, translation.providerName())
        jobs.setTotalItems(jobId, numbers.size)
        val done = AtomicInteger(0)
        val failed = AtomicInteger(0)
        // 影片级并发：设置中的「并发数」= 同时刮削的影片数；每个影片内部数据源并发由 ScraperChain 处理
        val executor = Executors.newFixedThreadPool(props.scraper.concurrency.coerceAtLeast(1))
        try {
            numbers.map { number ->
                executor.submit {
                    try {
                        // 任务被用户停止：未开始的影片直接跳过（进行中的刮削会执行完）
                        if (jobControl.isCancelled(jobId)) return@submit
                        val (source, meta) = chain.scrapeWithSource(number)

                        val autoTranslate = props.scraper.autoTranslate
                        // 标题翻译成中文（失败回退原文）；自动翻译关闭时直接用源站标题
                        val titleOrig = meta.title
                        // 翻译失败时回退源站中文标题（javdb origin-title / missav h1，繁体）→ 统一繁转简
                        val titleZh = if (autoTranslate) {
                            meta.title?.let { translation.translateToZh(it) }
                                ?: meta.titleZh?.takeIf { it.isNotBlank() }?.let { translation.toSimplified(it) }
                        } else null
                        // 简介仅保留简体中文结果：翻译/繁简转换成功只留译文，翻译失败保留原文
                        val plot = if (autoTranslate) {
                            meta.plot?.let { p -> translation.translateToZh(p) ?: p }
                        } else meta.plot
                        // 类别标签翻译成简体中文（词表重复度高，走缓存；失败保留原文）
                        // 画质/尺寸类标签（高清、4K、1080P 等）无内容价值，翻译前后都过滤
                        val genres = meta.genres
                            .filter { !qualityTagRegex.containsMatchIn(it) }
                            .map { if (autoTranslate) translation.translateLabel(it) else it }
                            .filter { it.isNotBlank() && !qualityTagRegex.containsMatchIn(it) }
                            .distinct()

                        movies.markScraped(
                            number = number,
                            // 元数据标题统一为「番号-中文标题」（title_orig 保留原题，NFO originaltitle / {title} 模板用）
                            title = (titleZh ?: meta.title)?.let { "$number-$it" },
                            titleZh = meta.titleZh, titleOrig = titleOrig,
                            releaseDate = meta.releaseDate,
                            director = meta.director, maker = meta.maker, label = meta.label,
                            series = meta.series, durationMin = meta.durationMin,
                            coverUrl = meta.coverUrl, previewUrls = meta.previewUrls, plot = plot,
                            source = source,
                            actresses = meta.actresses, genres = genres,
                            coverSource = meta.coverSource, previewSource = meta.previewSource,
                            coverCandidates = meta.coverCandidates,
                        )
                        imageStore.downloadAll(
                            number, meta.coverUrl, meta.previewUrls,
                            source = source, coverSource = meta.coverSource, previewSource = meta.previewSource,
                            coverCandidates = meta.coverCandidates,
                        )
                        log.info("刮削成功 {} 来源={} 标题={}", number, source, titleZh ?: meta.title ?: "—")
                    } catch (e: Exception) {
                        // 部分异常 message 为 null，带上异常类名便于诊断
                        val reason = e.message?.takeIf { it.isNotBlank() } ?: "异常 ${e::class.simpleName}"
                        failed.incrementAndGet()
                        movies.markFailed(number, reason)
                        log.warn("刮削失败 {}: {}", number, reason, e)
                        jobs.appendError(jobId, number, reason)
                    }
                    jobs.updateProgress(jobId, done.incrementAndGet(), failed.get())
                }
            }.forEach { runCatching { it.get() } }
        } finally {
            executor.shutdown()
        }
        if (jobControl.isCancelled(jobId)) throw top.sincs.sinjav.job.JobCancelledException()
        log.info("刮削完成 jobId={} 成功={} 失败={}", jobId, done.get() - failed.get(), failed.get())
        jobs.finish(jobId, "completed")
    }
}
