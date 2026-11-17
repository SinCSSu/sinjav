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
import top.sincs.sinjav.api.dto.ScanRequest
import top.sincs.sinjav.api.dto.ScrapeRequest
import top.sincs.sinjav.job.JobService
import top.sincs.sinjav.job.PipelineParams
import top.sincs.sinjav.organize.OrganizeParams
import top.sincs.sinjav.scan.ScanParams
import top.sincs.sinjav.scraper.ScrapeParams

@Validated
@RestController
@RequestMapping("/api/v1")
class TriggerControllers(private val jobService: JobService) {

    @PostMapping("/scans")
    fun triggerScan(@Valid @RequestBody req: ScanRequest): ResponseEntity<JobAccepted> {
        val job = jobService.triggerScan(
            ScanParams(
                sourceDirs = req.sourceDirs,
                recursive = req.recursive,
                extensions = req.extensions?.map { it.trim().lowercase() }?.toSet() ?: emptySet(),
            ),
        )
        return ResponseEntity.accepted().body(JobAccepted.of(job))
    }

    @PostMapping("/scrape")
    fun triggerScrape(@RequestBody req: ScrapeRequest): ResponseEntity<JobAccepted> {
        req.strategy?.let { require(it in setOf("fallback", "merge")) { "strategy 必须为 fallback 或 merge" } }
        val job = jobService.triggerScrape(ScrapeParams(req.numbers, req.sources, req.strategy))
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
                scan = req.scan?.let { ScanParams(it.sourceDirs, it.recursive, it.extensions?.toSet() ?: emptySet()) },
                scrape = req.scrape?.let { ScrapeParams(it.numbers, it.sources, it.strategy) },
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
