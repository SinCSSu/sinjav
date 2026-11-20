package top.sincs.sinjav.api

import jakarta.validation.Valid
import jakarta.validation.constraints.NotEmpty
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import top.sincs.sinjav.api.dto.JobAccepted
import top.sincs.sinjav.api.dto.OrganizePreviewRequest
import top.sincs.sinjav.api.dto.OrganizeRequest
import top.sincs.sinjav.api.dto.PipelineRequest
import top.sincs.sinjav.api.dto.ScanCommitRequest
import top.sincs.sinjav.api.dto.ScanPreviewRequest
import top.sincs.sinjav.api.dto.ScanRequest
import top.sincs.sinjav.api.dto.ScrapeApplyRequest
import top.sincs.sinjav.api.dto.ScrapePreviewRequest
import top.sincs.sinjav.api.dto.ScrapePreviewResponse
import top.sincs.sinjav.api.dto.ScrapeRequest
import top.sincs.sinjav.api.dto.ScrapeTranslateRequest
import top.sincs.sinjav.api.dto.ScrapeTranslateResponse
import top.sincs.sinjav.job.JobService
import top.sincs.sinjav.job.PipelineParams
import top.sincs.sinjav.organize.Organizer
import top.sincs.sinjav.organize.OrganizeItem
import top.sincs.sinjav.organize.OrganizeParams
import top.sincs.sinjav.organize.OrganizePreviewResponse
import top.sincs.sinjav.scan.ScanCommitResult
import top.sincs.sinjav.scan.ScanCommitService
import top.sincs.sinjav.scan.ScanParams
import top.sincs.sinjav.scan.ScanPreviewResponse
import top.sincs.sinjav.scan.ScanPreviewService
import top.sincs.sinjav.scraper.ScrapeCompareService
import top.sincs.sinjav.scraper.ScrapeParams

@Validated
@RestController
@RequestMapping("/api/v1")
class TriggerControllers(
    private val jobService: JobService,
    private val scanPreview: ScanPreviewService,
    private val scanCommit: ScanCommitService,
    private val organizer: Organizer,
    private val compare: ScrapeCompareService,
) {

    @PostMapping("/scans")
    fun triggerScan(@Valid @RequestBody req: ScanRequest): ResponseEntity<JobAccepted> {
        req.filenamePattern?.let { validateRegex(it) }
        val job = jobService.triggerScan(
            ScanParams(
                sourceDirs = req.sourceDirs,
                recursive = req.recursive,
                extensions = req.extensions?.map { it.trim().lowercase() }?.toSet() ?: emptySet(),
                minFileSizeMb = req.minFileSizeMb,
                excludePaths = req.excludePaths?.toSet() ?: emptySet(),
                filenamePattern = req.filenamePattern,
            ),
        )
        return ResponseEntity.accepted().body(JobAccepted.of(job))
    }

    /** 预览扫描：同步遍历返回候选文件列表，不写数据库 */
    @PostMapping("/scans/preview")
    fun previewScan(@Valid @RequestBody req: ScanPreviewRequest): ScanPreviewResponse {
        req.filenamePattern?.let { validateRegex(it) }
        return scanPreview.preview(
            sourceDirs = req.sourceDirs,
            recursive = req.recursive,
            extensions = req.extensions?.map { it.trim().lowercase() }?.toSet(),
            minFileSizeMb = req.minFileSizeMb,
            filenamePattern = req.filenamePattern,
        )
    }

    /** 扫描确认落库：将预览后保留的文件直接写入文件记录 */
    @PostMapping("/scans/commit")
    fun commitScan(@Valid @RequestBody req: ScanCommitRequest): ScanCommitResult =
        scanCommit.commit(req.items)

    private fun validateRegex(pattern: String) {
        runCatching { Regex(pattern) }.getOrElse {
            throw IllegalArgumentException("文件名正则非法: ${it.message}")
        }
    }

    @PostMapping("/scrape")
    fun triggerScrape(@RequestBody req: ScrapeRequest): ResponseEntity<JobAccepted> {
        val job = jobService.triggerScrape(ScrapeParams(req.numbers))
        return ResponseEntity.accepted().body(JobAccepted.of(job))
    }

    /** 多源对比刮削预览：同步并发抓取全部数据源，返回各源原始字段值与合并默认来源（不落库） */
    @PostMapping("/scrape/preview")
    fun scrapePreview(@RequestBody req: ScrapePreviewRequest): ScrapePreviewResponse = compare.preview(req.number)

    /** 多源对比刮削落库：按逐字段来源选择（各源/留空/手动输入）与输入框当前内容写库并下载图片（同步，不自动翻译） */
    @PostMapping("/scrape/apply")
    fun scrapeApply(@RequestBody req: ScrapeApplyRequest): Map<String, String> {
        compare.apply(req)
        return mapOf("number" to req.number, "status" to "scraped")
    }

    /** 对比界面手动翻译：翻译/繁→简转换标题、简介，逐个翻译类别，返回译文供回填输入框（同步，不落库） */
    @PostMapping("/scrape/translate")
    fun scrapeTranslate(@RequestBody req: ScrapeTranslateRequest): ScrapeTranslateResponse =
        compare.translateFields(req)

    /** 手动落库：手动录入元数据并上传封面/剧照（multipart，同步落库，不抓取不翻译） */
    @PostMapping("/scrape/manual", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun scrapeManual(
        @RequestParam number: String,
        @RequestParam(required = false) title: String?,
        @RequestParam(required = false) titleOrig: String?,
        @RequestParam(required = false) plot: String?,
        @RequestParam(required = false) releaseDate: String?,
        @RequestParam(required = false) durationMin: Int?,
        @RequestParam(required = false) director: String?,
        @RequestParam(required = false) maker: String?,
        @RequestParam(required = false) label: String?,
        @RequestParam(required = false) series: String?,
        @RequestParam(required = false, defaultValue = "") actresses: String,
        @RequestParam(required = false, defaultValue = "") genres: String,
        @RequestParam(required = false) coverUrl: String?,
        @RequestParam(required = false) previewUrls: String?,
        @RequestPart(required = false) cover: MultipartFile?,
        @RequestPart(required = false) previews: List<MultipartFile>?,
    ): Map<String, String> {
        val n = compare.manual(
            number = number,
            title = title, titleOrig = titleOrig, plot = plot,
            releaseDate = releaseDate?.trim()?.takeIf { it.isNotBlank() }
                ?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() },
            durationMin = durationMin,
            director = director, maker = maker, label = label, series = series,
            actresses = compare.splitListValues(actresses),
            genres = compare.splitListValues(genres),
            coverUrl = coverUrl,
            previewUrls = compare.splitListValues(previewUrls),
            cover = cover?.takeIf { !it.isEmpty }?.bytes,
            previews = previews.orEmpty().filter { !it.isEmpty }.map { it.bytes },
        )
        return mapOf("number" to n, "status" to "scraped")
    }

    @PostMapping("/organize")
    fun triggerOrganize(@RequestBody req: OrganizeRequest): ResponseEntity<JobAccepted> {
        require(req.mode in MODES) { "mode 必须为 hardlink/symlink/move/copy" }
        require(req.targetDir.isNotBlank()) { "targetDir 必填" }
        req.conflictPolicy?.let { require(it in POLICIES) { "conflictPolicy 必须为 skip/overwrite/rename" } }
        req.items?.forEach { item ->
            item.mode?.let { require(it in MODES) { "items.mode 必须为 hardlink/symlink/move/copy" } }
        }
        val job = jobService.triggerOrganize(
            OrganizeParams(
                targetDir = req.targetDir, mode = req.mode, namingTemplate = req.namingTemplate,
                conflictPolicy = req.conflictPolicy, generateNfo = req.generateNfo,
                downloadImages = req.downloadImages, numbers = req.numbers,
                items = req.items?.map { OrganizeItem(it.recordId, it.targetPath, it.mode) },
            ),
        )
        return ResponseEntity.accepted().body(JobAccepted.of(job))
    }

    /** 整理预览：同步计算每条文件记录的目标路径（不执行、不写库），供前端预览列展示与修改 */
    @PostMapping("/organize/preview")
    fun previewOrganize(@RequestBody req: OrganizePreviewRequest): OrganizePreviewResponse {
        require(req.mode in MODES) { "mode 必须为 hardlink/symlink/move/copy" }
        require(req.targetDir.isNotBlank()) { "targetDir 必填" }
        req.conflictPolicy?.let { require(it in POLICIES) { "conflictPolicy 必须为 skip/overwrite/rename" } }
        return organizer.preview(req.recordIds, OrganizeParams(req.targetDir, req.mode, req.namingTemplate, req.conflictPolicy))
    }

    @PostMapping("/pipeline")
    fun triggerPipeline(@RequestBody req: PipelineRequest): ResponseEntity<JobAccepted> {
        val job = jobService.triggerPipeline(
            PipelineParams(
                scan = req.scan?.let {
                    ScanParams(
                        sourceDirs = it.sourceDirs,
                        recursive = it.recursive,
                        extensions = it.extensions?.toSet() ?: emptySet(),
                        minFileSizeMb = it.minFileSizeMb,
                        excludePaths = it.excludePaths?.toSet() ?: emptySet(),
                        filenamePattern = it.filenamePattern,
                    )
                },
                scrape = req.scrape?.let { ScrapeParams(it.numbers) },
                organize = req.organize?.let {
                    require(it.mode in MODES) { "organize.mode 必须为 hardlink/symlink/move/copy" }
                    require(!it.targetDir.isNullOrBlank()) { "organize.targetDir 必填" }
                    OrganizeParams(
                        it.targetDir, it.mode, it.namingTemplate, it.conflictPolicy,
                        it.generateNfo, it.downloadImages, it.numbers,
                    )
                },
            ),
        )
        return ResponseEntity.accepted().body(JobAccepted.of(job))
    }
}

/** scan 请求的校验 DTO（sourceDirs 必填非空，见 dto.ScanRequest） */

private val MODES = setOf("hardlink", "symlink", "move", "copy")
private val POLICIES = setOf("skip", "overwrite", "rename")
