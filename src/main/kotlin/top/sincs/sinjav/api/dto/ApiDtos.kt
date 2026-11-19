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
    val minFileSizeMb: Long? = null,
    val excludePaths: List<String>? = null,
    val filenamePattern: String? = null,
)

data class ScanPreviewRequest(
    @field:jakarta.validation.constraints.NotEmpty(message = "sourceDirs 不能为空")
    val sourceDirs: List<String>,
    val recursive: Boolean = true,
    val extensions: List<String>? = null,
    val minFileSizeMb: Long? = null,
    val filenamePattern: String? = null,
)

data class ScanCommitRequest(
    @field:jakarta.validation.constraints.NotEmpty(message = "items 不能为空")
    val items: List<top.sincs.sinjav.scan.ScanCommitItem>,
)

data class ScrapeRequest(
    val numbers: List<String>? = null,
)

/** 多源对比刮削预览请求（单番号，同步返回各源原始结果） */
data class ScrapePreviewRequest(
    @field:jakarta.validation.constraints.NotBlank(message = "number 必填")
    val number: String,
)

/**
 * 多源对比刮削预览：fields[fieldKey][sourceId] = 该源抓到的原文值（仅含该源有值的字段）；
 * defaults[fieldKey] = 按当前合并规则的默认来源（source-order 靠前的源优先，无值为 null）。
 */
data class ScrapePreviewResponse(
    val number: String,
    val sources: List<String>,
    val fields: Map<String, Map<String, String>>,
    val defaults: Map<String, String?>,
)

/** 单字段的来源选择：source=null 留空（清空该字段）；manual 使用 value 手动输入；其余为数据源 id，value 为该源原文 */
data class ScrapeFieldChoice(
    val source: String? = null,
    val value: String? = null,
)

/** 多源对比刮削落库：逐字段应用用户选择的来源/手动值，翻译后写库并下载图片 */
data class ScrapeApplyRequest(
    @field:jakarta.validation.constraints.NotBlank(message = "number 必填")
    val number: String,
    /**
     * 原记录番号（对比界面编辑番号后重刮场景）：与 number 不同时把该记录改用 number 落库
     * （movies.number 与 file_records.number 同步更新）；省略或相同则无重命名。
     */
    val recordNumber: String? = null,
    val fields: Map<String, ScrapeFieldChoice> = emptyMap(),
)

data class OrganizeRequest(
    /** 目标目录：整理不再使用全局配置，每次整理必须指定 */
    @field:jakarta.validation.constraints.NotBlank(message = "targetDir 必填")
    val targetDir: String,
    /** 整理方式：整理不再使用全局配置，每次整理必须指定 */
    @field:jakarta.validation.constraints.NotBlank(message = "mode 必填")
    val mode: String,
    val namingTemplate: String? = null,
    val conflictPolicy: String? = null,
    val generateNfo: Boolean? = null,
    val downloadImages: Boolean? = null,
    val numbers: List<String>? = null,
    /** 按文件记录逐条整理（可覆盖目标路径/整理方式），优先于 numbers */
    val items: List<OrganizeItemRequest>? = null,
)

data class OrganizeItemRequest(
    @field:jakarta.validation.constraints.NotNull(message = "recordId 不能为空")
    val recordId: Long,
    /** 完整目标路径（目录+文件名）；省略时按命名模板渲染 */
    val targetPath: String? = null,
    /** 整理方式覆盖；省略时使用请求级 mode */
    val mode: String? = null,
)

data class OrganizePreviewRequest(
    @field:jakarta.validation.constraints.NotEmpty(message = "recordIds 不能为空")
    val recordIds: List<Long>,
    /** 目标目录：每次预览必须指定 */
    @field:jakarta.validation.constraints.NotBlank(message = "targetDir 必填")
    val targetDir: String,
    /** 整理方式：每次预览必须指定 */
    @field:jakarta.validation.constraints.NotBlank(message = "mode 必填")
    val mode: String,
    val namingTemplate: String? = null,
    val conflictPolicy: String? = null,
)

data class PipelineRequest(
    val scan: ScanRequest? = null,
    val scrape: ScrapeRequest? = null,
    val organize: OrganizeRequest? = null,
)

data class SettingsUpdate(
    val sourceOrder: String? = null,
    val userAgent: String? = null,
    val javdbCookie: String? = null,
    val proxyHost: String? = null,
    val proxyPort: Int? = null,
    /** CF 过盾代理地址（如 trawl MITM http://127.0.0.1:8192）；空则全部走全局代理/直连 */
    val cfProxyUrl: String? = null,
    /** 需要 CF 过盾的数据源 id，逗号分隔（如 "javdb"） */
    val cfProxySources: String? = null,
    val requestIntervalMs: Long? = null,
    val concurrency: Int? = null,
    val timeoutMs: Long? = null,
    val cfTimeoutMs: Long? = null,
    val namingTemplate: String? = null,
    val conflictPolicy: String? = null,
    val generateNfo: Boolean? = null,
    val downloadImages: Boolean? = null,
    // 翻译 API（DeepL > 百度 > Google 兜底）
    val deeplApiKey: String? = null,
    val baiduTranslateAppId: String? = null,
    val baiduTranslateSecret: String? = null,
    // Emby 集成
    val embyBaseUrl: String? = null,
    val embyApiKey: String? = null,
    /** Emby 专用 HTTP 代理（留空直连） */
    val embyProxyHost: String? = null,
    val embyProxyPort: Int? = null,
    /** 图片缓存过期天数（0 = 永久保留） */
    val imagesTtlDays: Int? = null,
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
    val number: String, val title: String?, val titleOrig: String?, val releaseDate: String?, val maker: String?,
    val actresses: List<String>, val coverUrl: String?, val status: String,
    val plot: String? = null,
) {
    companion object {
        fun of(m: Movie, actresses: List<String>) = MovieSummaryDto(
            number = m.number, title = m.title, titleOrig = m.titleOrig, releaseDate = m.releaseDate?.toString(),
            maker = m.maker, actresses = actresses, coverUrl = m.coverUrl, status = m.status,
            plot = m.plot,
        )
    }
}

data class MovieDetailDto(
    val number: String, val title: String?, val titleZh: String?, val titleOrig: String?, val rawNumber: String?,
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

/** 整理弹窗列表行（文件记录 + 影片标题/封面） */
data class OrganizeRecordDto(
    val recordId: Long, val sourcePath: String, val fileName: String, val fileSize: Long,
    val number: String?, val title: String?, val coverUrl: String?,
    val organizeStatus: String, val targetPath: String?, val linkMode: String?,
)

data class SettingsDto(
    val sourceOrder: String, val userAgent: String, val javdbCookie: String,
    val proxyHost: String, val proxyPort: Int, val requestIntervalMs: Long, val concurrency: Int,
    val timeoutMs: Long, val cfTimeoutMs: Long,
    val cfProxyUrl: String, val cfProxySources: String,
    val namingTemplate: String, val conflictPolicy: String,
    val generateNfo: Boolean, val downloadImages: Boolean,
    val deeplApiKey: String, val baiduTranslateAppId: String, val baiduTranslateSecret: String,
    val embyBaseUrl: String, val embyApiKey: String,
    val embyProxyHost: String, val embyProxyPort: Int,
    val videoExtensions: String, val minFileSizeMb: Long,
    /** 图片缓存过期天数（0 = 永久保留） */
    val imagesTtlDays: Int,
) {
    companion object {
        fun from(props: AppProperties) = SettingsDto(
            sourceOrder = props.scraper.sourceOrder,
            userAgent = props.scraper.userAgent, javdbCookie = props.scraper.javdbCookie,
            proxyHost = props.scraper.proxyHost, proxyPort = props.scraper.proxyPort,
            cfProxyUrl = props.scraper.cfProxyUrl, cfProxySources = props.scraper.cfProxySources,
            requestIntervalMs = props.scraper.requestIntervalMs, concurrency = props.scraper.concurrency,
            timeoutMs = props.scraper.timeoutMs, cfTimeoutMs = props.scraper.cfTimeoutMs,
            namingTemplate = props.organize.namingTemplate, conflictPolicy = props.organize.conflictPolicy,
            generateNfo = props.organize.generateNfo, downloadImages = props.organize.downloadImages,
            deeplApiKey = props.scraper.deeplApiKey,
            baiduTranslateAppId = props.scraper.baiduTranslateAppId,
            baiduTranslateSecret = props.scraper.baiduTranslateSecret,
            embyBaseUrl = props.emby.baseUrl, embyApiKey = props.emby.apiKey,
            embyProxyHost = props.emby.proxyHost, embyProxyPort = props.emby.proxyPort,
            videoExtensions = props.scan.videoExtensions, minFileSizeMb = props.scan.minFileSizeMb,
            imagesTtlDays = props.images.ttlDays,
        )
    }
}
