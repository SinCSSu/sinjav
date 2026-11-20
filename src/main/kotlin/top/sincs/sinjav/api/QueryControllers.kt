package top.sincs.sinjav.api

import tools.jackson.databind.ObjectMapper
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import top.sincs.sinjav.api.dto.FileRecordDto
import top.sincs.sinjav.api.dto.JobDto
import top.sincs.sinjav.api.dto.MovieDetailDto
import top.sincs.sinjav.api.dto.MovieSummaryDto
import top.sincs.sinjav.api.dto.OrganizeRecordDto
import top.sincs.sinjav.api.dto.PageDto
import top.sincs.sinjav.api.dto.SettingsDto
import top.sincs.sinjav.api.dto.SettingsUpdate
import top.sincs.sinjav.config.AppProperties
import top.sincs.sinjav.config.SettingsService
import top.sincs.sinjav.domain.FileRecordRepository
import top.sincs.sinjav.domain.JobRepository
import top.sincs.sinjav.domain.MovieRepository

@RestController
@RequestMapping("/api/v1")
class MovieController(
    private val movies: MovieRepository,
    private val fileRecords: FileRecordRepository,
    private val imageStore: top.sincs.sinjav.scraper.ImageStore,
) {

    @GetMapping("/movies")
    fun list(
        @RequestParam(defaultValue = "1") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
        @RequestParam(required = false) keyword: String?,
        @RequestParam(required = false) status: String?,
        @RequestParam(defaultValue = "created_at") sort: String,
        @RequestParam(defaultValue = "desc") order: String,
    ): PageDto<MovieSummaryDto> {
        require(status == null || status in setOf("pending", "scraped", "failed")) { "非法 status" }
        val pg = page.coerceAtLeast(1)
        val sz = size.coerceIn(1, 200)
        val p = movies.list(pg, sz, keyword, status, sort, order)
        return PageDto.of(pg, sz, p.total, p.items.map { MovieSummaryDto.of(it, p.actressesByMovie[it.id] ?: emptyList()) })
    }

    @GetMapping("/movies/{number}")
    fun detail(@PathVariable number: String): ResponseEntity<MovieDetailDto> {
        val m = movies.findByNumber(number) ?: throw NoSuchElementException("影片不存在: $number")
        return ResponseEntity.ok(
            MovieDetailDto(
                number = m.number, title = m.title, titleZh = m.titleZh, titleOrig = m.titleOrig, rawNumber = m.rawNumber,
                releaseDate = m.releaseDate?.toString(), director = m.director, maker = m.maker,
                label = m.label, series = m.series, durationMin = m.durationMin, coverUrl = m.coverUrl,
                previewUrls = m.previewUrls, plot = m.plot, source = m.source, status = m.status,
                failReason = m.failReason, actresses = movies.findActresses(m.id),
                genres = movies.findGenres(m.id),
                scrapedAt = m.scrapedAt?.toString(), createdAt = m.createdAt.toString(),
                updatedAt = m.updatedAt.toString(),
                fileRecords = fileRecords.findByNumber(m.number).map { FileRecordDto.of(it) },
            ),
        )
    }

    /**
     * 删除影片记录：影片 + 关联表 + 该番号的文件记录 + 本地刮削图片缓存。
     * 不删除任何视频文件（零侵入）。
     */
    @org.springframework.web.bind.annotation.DeleteMapping("/movies/{number}")
    fun delete(@PathVariable number: String): ResponseEntity<Void> {
        if (!movies.delete(number)) throw NoSuchElementException("影片不存在: $number")
        fileRecords.deleteByNumber(number)
        imageStore.delete(number)
        org.slf4j.LoggerFactory.getLogger(MovieController::class.java)
            .info("影片记录已删除 {}（关联文件记录与本地图片缓存一并清理）", number)
        return ResponseEntity.noContent().build()
    }
}

@RestController
@RequestMapping("/api/v1")
class FileRecordController(private val fileRecords: FileRecordRepository) {

    @GetMapping("/file-records")
    fun list(
        @RequestParam(defaultValue = "1") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
        @RequestParam(required = false) number: String?,
        @RequestParam(required = false) recognizeStatus: String?,
        @RequestParam(required = false) organizeStatus: String?,
    ): PageDto<FileRecordDto> {
        require(recognizeStatus == null || recognizeStatus in setOf("recognized", "unrecognized", "filtered")) { "非法 recognizeStatus" }
        require(organizeStatus == null || organizeStatus in setOf("pending", "organized", "failed", "skipped")) { "非法 organizeStatus" }
        val pg = page.coerceAtLeast(1)
        val sz = size.coerceIn(1, 200)
        val p = fileRecords.list(pg, sz, number, recognizeStatus, organizeStatus)
        return PageDto.of(pg, sz, p.total, p.items.map { FileRecordDto.of(it) })
    }

    /** 整理弹窗列表：按 group（pending/organized）分组返回文件记录（联出影片标题/封面） */
    @GetMapping("/organize/records")
    fun organizeRecords(
        @RequestParam(defaultValue = "1") page: Int,
        @RequestParam(defaultValue = "100") size: Int,
        @RequestParam group: String,
        @RequestParam(required = false) keyword: String?,
    ): PageDto<OrganizeRecordDto> {
        require(group in setOf("pending", "organized")) { "group 必须为 pending/organized" }
        val pg = page.coerceAtLeast(1)
        val sz = size.coerceIn(1, 500)
        val (items, total) = fileRecords.listForOrganize(pg, sz, group, keyword)
        return PageDto.of(
            pg, sz, total,
            items.map {
                OrganizeRecordDto(
                    recordId = it.id, sourcePath = it.sourcePath, fileName = it.fileName,
                    fileSize = it.fileSize, number = it.number, title = it.title, coverUrl = it.coverUrl,
                    organizeStatus = it.organizeStatus, targetPath = it.targetPath, linkMode = it.linkMode,
                )
            },
        )
    }
}

@RestController
@RequestMapping("/api/v1")
class JobController(
    private val jobs: JobRepository,
    private val objectMapper: ObjectMapper,
    private val jobService: top.sincs.sinjav.job.JobService,
) {

    @GetMapping("/jobs")
    fun list(
        @RequestParam(defaultValue = "1") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
        @RequestParam(required = false) type: String?,
        @RequestParam(required = false) status: String?,
    ): PageDto<JobDto> {
        require(type == null || type in setOf("scan", "scrape", "organize", "pipeline")) { "非法 type" }
        require(status == null || status in setOf("running", "completed", "failed", "cancelled")) { "非法 status" }
        val pg = page.coerceAtLeast(1)
        val sz = size.coerceIn(1, 200)
        val p = jobs.list(pg, sz, type, status)
        return PageDto.of(pg, sz, p.total, p.items.map { JobDto.of(it, objectMapper) })
    }

    @GetMapping("/jobs/{jobId}")
    fun detail(@PathVariable jobId: Long): JobDto {
        val job = jobs.findById(jobId) ?: throw NoSuchElementException("任务不存在: $jobId")
        return JobDto.of(job, objectMapper)
    }

    /** 停止运行中的任务（协作式取消，正在执行的条目会完成） */
    @PostMapping("/jobs/{jobId}/stop")
    fun stop(@PathVariable jobId: Long): JobDto {
        jobService.stop(jobId)
        val job = jobs.findById(jobId) ?: throw NoSuchElementException("任务不存在: $jobId")
        return JobDto.of(job, objectMapper)
    }
}

@RestController
@RequestMapping("/api/v1")
class SettingsController(
    private val props: AppProperties,
    private val settings: SettingsService,
    private val imageStore: top.sincs.sinjav.scraper.ImageStore,
) {

    @GetMapping("/settings")
    fun get(): SettingsDto = SettingsDto.from(props)

    @PutMapping("/settings")
    fun update(@RequestBody req: SettingsUpdate): SettingsDto {
        settings.update(req)
        return SettingsDto.from(props)
    }

    /** 手动清理过期图片缓存（按设置页配置的 TTL），返回删除的文件数与字节数 */
    @PostMapping("/settings/images/cleanup")
    fun cleanupImages(): top.sincs.sinjav.scraper.ImageStore.CleanupResult = imageStore.cleanupExpired()
}
