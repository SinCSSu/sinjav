package top.sincs.sinjav.api

import jakarta.validation.Valid
import jakarta.validation.constraints.NotEmpty
import org.springframework.http.ResponseEntity
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import top.sincs.sinjav.api.dto.JobAccepted
import top.sincs.sinjav.api.dto.OrganizeRequest
import top.sincs.sinjav.api.dto.PipelineRequest
import top.sincs.sinjav.api.dto.ScanCommitRequest
import top.sincs.sinjav.api.dto.ScanPreviewRequest
import top.sincs.sinjav.api.dto.ScanRequest
import top.sincs.sinjav.api.dto.ScrapeRequest
import top.sincs.sinjav.job.JobService
import top.sincs.sinjav.job.PipelineParams
import top.sincs.sinjav.organize.OrganizeParams
import top.sincs.sinjav.scan.ScanCommitResult
import top.sincs.sinjav.scan.ScanCommitService
import top.sincs.sinjav.scan.ScanParams
import top.sincs.sinjav.scan.ScanPreviewResponse
import top.sincs.sinjav.scan.ScanPreviewService
import top.sincs.sinjav.scraper.ScrapeParams

@Validated
@RestController
@RequestMapping("/api/v1")
class TriggerControllers(
    private val jobService: JobService,
    private val scanPreview: ScanPreviewService,
    private val scanCommit: ScanCommitService,
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

    @PostMapping("/organize")
    fun triggerOrganize(@RequestBody req: OrganizeRequest): ResponseEntity<JobAccepted> {
        req.mode?.let { require(it in setOf("hardlink", "symlink", "move", "copy")) { "mode 必须为 hardlink/symlink/move/copy" } }
        req.conflictPolicy?.let { require(it in setOf("skip", "overwrite", "rename")) { "conflictPolicy 必须为 skip/overwrite/rename" } }
        val job = jobService.triggerOrganize(
            OrganizeParams(
                targetDir = req.targetDir, mode = req.mode, namingTemplate = req.namingTemplate,
                conflictPolicy = req.conflictPolicy, generateNfo = req.generateNfo,
                downloadImages = req.downloadImages, numbers = req.numbers,
            ),
        )
        return ResponseEntity.accepted().body(JobAccepted.of(job))
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
