package top.sincs.sinjav.scraper

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import top.sincs.sinjav.domain.JobRepository
import top.sincs.sinjav.domain.MovieRepository
import top.sincs.sinjav.scan.NumberRecognizer

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
) {

    private val log = LoggerFactory.getLogger(ScrapeService::class.java)

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

        jobs.setTotalItems(jobId, numbers.size)
        var done = 0
        var failed = 0
        numbers.forEach { number ->
            try {
                val (source, meta) = chain.scrapeWithSource(number)

                // 标题翻译成中文（失败回退原文）
                val titleOrig = meta.title
                val titleZh = meta.title?.let { translation.translateToZh(it) } ?: meta.titleZh
                // 简介翻译成中文，中文在前、原简介另起一段
                val plot = meta.plot?.let { p ->
                    val pzh = translation.translateToZh(p)
                    if (pzh != null) "$pzh\n\n$p" else p
                }

                movies.markScraped(
                    number = number,
                    title = titleZh ?: meta.title, titleZh = meta.titleZh, titleOrig = titleOrig,
                    releaseDate = meta.releaseDate,
                    director = meta.director, maker = meta.maker, label = meta.label,
                    series = meta.series, durationMin = meta.durationMin,
                    coverUrl = meta.coverUrl, previewUrls = meta.previewUrls, plot = plot,
                    source = source,
                    actresses = meta.actresses, genres = meta.genres,
                )
                imageStore.downloadAll(number, meta.coverUrl, meta.previewUrls)
                log.info("刮削成功 {}: {}", number, titleZh ?: meta.title)
            } catch (e: Exception) {
                failed++
                movies.markFailed(number, e.message ?: "unknown")
                log.warn("刮削失败 {}: {}", number, e.message)
                jobs.appendError(jobId, number, e.message ?: "unknown")
            }
            done++
            jobs.updateProgress(jobId, done, failed)
        }
        jobs.finish(jobId, "completed")
    }
}
