package top.sincs.sinjav.api.dto

import top.sincs.sinjav.config.AppProperties
import top.sincs.sinjav.domain.FileRecord
import top.sincs.sinjav.domain.Job
import top.sincs.sinjav.domain.Movie
import tools.jackson.databind.ObjectMapper

// ---------- 请求 ----------

data class ScanRequest(
    @field:jakarta.validation.constraints.NotEmpty(message = "sourceDirs 不能为空")
    val sourceDirs: List<String>,
    val recursive: Boolean = true,
    val extensions: List<String>? = null,
)

data class ScrapeRequest(
    val numbers: List<String>? = null,
    val sources: List<String>? = null,
    val strategy: String? = null,
)

data class OrganizeRequest(
    val targetDir: String? = null,
    val mode: String? = null,
    val namingTemplate: String? = null,
    val conflictPolicy: String? = null,
    val generateNfo: Boolean? = null,
    val downloadImages: Boolean? = null,
    val numbers: List<String>? = null,
)

data class PipelineRequest(
    val scan: ScanRequest? = null,
    val scrape: ScrapeRequest? = null,
    val organize: OrganizeRequest? = null,
)

data class SettingsUpdate(
    val sourceOrder: String? = null,
    val strategy: String? = null,
    val userAgent: String? = null,
    val javdbCookie: String? = null,
    val proxyHost: String? = null,
    val proxyPort: Int? = null,
    val requestIntervalMs: Long? = null,
    val concurrency: Int? = null,
    val targetDir: String? = null,
    val mode: String? = null,
    val namingTemplate: String? = null,
    val conflictPolicy: String? = null,
    val generateNfo: Boolean? = null,
    val downloadImages: Boolean? = null,
)

// ---------- 响应 ----------

data class JobAccepted(val jobId: Long, val type: String, val status: String) {
    companion object {
        fun of(job: Job) = JobAccepted(job.id, job.type, job.status)
    }
}

data class JobError(val item: String, val reason: String)

data class JobDto(
    val id: Long, val type: String, val status: String,
    val params: Any?, val totalItems: Int, val doneItems: Int, val failItems: Int,
    val errors: List<JobError>, val startedAt: String, val finishedAt: String?,
) {
    companion object {
        fun of(job: Job, objectMapper: ObjectMapper): JobDto {
            val errors = parseErrors(job.errorDetail)
            val params: Map<*, *>? = job.params?.let { p ->
                runCatching { objectMapper.readValue(p, Map::class.java) }.getOrNull()
            }
            return JobDto(
                id = job.id, type = job.type, status = job.status,
                params = params,
                totalItems = job.totalItems, doneItems = job.doneItems, failItems = job.failItems,
                errors = errors, startedAt = job.startedAt.toString(), finishedAt = job.finishedAt?.toString(),
            )
        }

        private fun parseErrors(json: String?): List<JobError> {
            if (json.isNullOrBlank()) return emptyList()
            return Regex("\"((?:[^\"\\\\]|\\\\.)*)\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
                .findAll(json)
                .map { JobError(unescape(it.groupValues[1]), unescape(it.groupValues[2])) }
                .toList()
        }

        private fun unescape(s: String): String =
            s.replace("\\\\\"", "\"").replace("\\\\n", "\n").replace("\\\\\\\\", "\\\\")
    }
}

data class PageMeta(val page: Int, val size: Int, val total: Long, val totalPages: Int)

data class MovieSummaryDto(
    val number: String, val title: String?, val releaseDate: String?, val maker: String?,
    val actresses: List<String>, val coverUrl: String?, val status: String,
) {
    companion object {
        fun of(m: Movie, actresses: List<String>) = MovieSummaryDto(
            number = m.number, title = m.title, releaseDate = m.releaseDate?.toString(),
            maker = m.maker, actresses = actresses, coverUrl = m.coverUrl, status = m.status,
        )
    }
}

data class MovieDetailDto(
    val number: String, val title: String?, val titleZh: String?, val rawNumber: String?,
    val releaseDate: String?, val director: String?, val maker: String?, val label: String?,
    val series: String?, val durationMin: Int?, val coverUrl: String?, val previewUrls: List<String>,
    val plot: String?, val source: String?, val status: String, val failReason: String?,
    val actresses: List<String>, val genres: List<String>,
    val scrapedAt: String?, val createdAt: String, val updatedAt: String,
    val fileRecords: List<FileRecordDto>,
)

data class FileRecordDto(
    val id: Long, val sourcePath: String, val fileName: String, val fileSize: Long,
    val fileMtime: String?, val number: String?, val recognizeStatus: String,
    val targetPath: String?, val linkMode: String?, val organizeStatus: String,
    val organizeError: String?, val createdAt: String,
) {
    companion object {
        fun of(r: FileRecord) = FileRecordDto(
            id = r.id, sourcePath = r.sourcePath, fileName = r.fileName, fileSize = r.fileSize,
            fileMtime = r.fileMtime?.toString(), number = r.number, recognizeStatus = r.recognizeStatus,
            targetPath = r.targetPath, linkMode = r.linkMode, organizeStatus = r.organizeStatus,
            organizeError = r.organizeError, createdAt = r.createdAt.toString(),
        )
    }
}

data class SettingsDto(
    val sourceOrder: String, val strategy: String, val userAgent: String, val javdbCookie: String,
    val proxyHost: String, val proxyPort: Int, val requestIntervalMs: Long, val concurrency: Int,
    val targetDir: String, val mode: String, val namingTemplate: String, val conflictPolicy: String,
    val generateNfo: Boolean, val downloadImages: Boolean,
) {
    companion object {
        fun from(props: AppProperties) = SettingsDto(
            sourceOrder = props.scraper.sourceOrder, strategy = props.scraper.strategy,
            userAgent = props.scraper.userAgent, javdbCookie = props.scraper.javdbCookie,
            proxyHost = props.scraper.proxyHost, proxyPort = props.scraper.proxyPort,
            requestIntervalMs = props.scraper.requestIntervalMs, concurrency = props.scraper.concurrency,
            targetDir = props.organize.targetDir, mode = props.organize.mode,
            namingTemplate = props.organize.namingTemplate, conflictPolicy = props.organize.conflictPolicy,
            generateNfo = props.organize.generateNfo, downloadImages = props.organize.downloadImages,
        )
    }
}
