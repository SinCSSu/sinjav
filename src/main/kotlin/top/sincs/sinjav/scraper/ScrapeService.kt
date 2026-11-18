package top.sincs.sinjav.scraper

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import top.sincs.sinjav.domain.JobRepository
import top.sincs.sinjav.domain.MovieRepository
import top.sincs.sinjav.scan.NumberRecognizer

data class ScrapeParams(
    val numbers: List<String>? = null,
    val sources: List<String>? = null,
    val strategy: String? = null,
)

@Service
class ScrapeService(
    private val chain: ScraperChain,
    private val movies: MovieRepository,
    private val jobs: JobRepository,
    private val recognizer: NumberRecognizer,
    private val imageStore: ImageStore,
) {

    private val log = LoggerFactory.getLogger(ScrapeService::class.java)

    /**
     * 执行刮削（同步）。numbers 为空时刮削全部 pending/failed 番号（可重试）。
     * 失败的番号记录原因，再次触发即可重试。
     */
    fun execute(jobId: Long, params: ScrapeParams) {
        val numbers = params.numbers?.takeIf { it.isNotEmpty() }?.mapNotNull { NumberRecognizer.normalize(it) }
            ?: movies.numbersNeedingScrape()

        jobs.setTotalItems(jobId, numbers.size)
        var done = 0
        var failed = 0
        numbers.forEach { number ->
            try {
                val (source, meta) = chain.scrapeWithSource(number, params.sources, params.strategy)
                movies.markScraped(
                    number = number,
                    title = meta.title, titleZh = meta.titleZh, releaseDate = meta.releaseDate,
                    director = meta.director, maker = meta.maker, label = meta.label,
                    series = meta.series, durationMin = meta.durationMin,
                    coverUrl = meta.coverUrl, previewUrls = meta.previewUrls, plot = meta.plot,
                    source = source,
                    actresses = meta.actresses, genres = meta.genres,
                )
                imageStore.downloadAll(number, meta.coverUrl, meta.previewUrls)
                log.info("刮削成功 {}: {}", number, meta.title)
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
