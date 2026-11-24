package top.sincs.sinjav.domain

import java.time.Instant
import java.time.LocalDate

data class FileRecord(
    val id: Long,
    val sourcePath: String,
    val fileName: String,
    val fileSize: Long,
    val fileMtime: Instant?,
    val number: String?,
    val recognizeStatus: String, // recognized | unrecognized | filtered
    val targetPath: String?,
    val linkMode: String?,
    val organizeStatus: String, // pending | organizing | organized | failed | skipped
    val organizeError: String?,
    val createdAt: Instant,
)

data class Movie(
    val id: Long,
    val number: String,
    val rawNumber: String?,
    val title: String?,
    val titleZh: String?,
    val titleOrig: String? = null, // 原始标题（标题已翻译为中文时保留）
    val releaseDate: LocalDate?,
    val director: String?,
    val maker: String?,
    val label: String?,
    val series: String?,
    val durationMin: Int?,
    val coverUrl: String?,
    val previewUrls: List<String>,
    /** 封面实际生效来源（下载成功后回写；多源合并落库时为候选首位来源） */
    val coverSource: String? = null,
    /** 剧照来源数据源 id（下载路由用） */
    val previewSource: String? = null,
    /** 封面候选列表（JSON 数组 [{"url","source"}]，下载失败自动换下一候选；javdb 沉底） */
    val coverCandidates: List<top.sincs.sinjav.scraper.CoverCandidate> = emptyList(),
    /** 剧照逐张来源（JSON 数组 [{"url","source"}]）：下载时按图路由；缺项回退 preview_source */
    val previewCandidates: List<top.sincs.sinjav.scraper.CoverCandidate> = emptyList(),
    val plot: String?,
    val source: String?,
    val status: String, // pending | scraped | failed
    val failReason: String?,
    val scrapedAt: Instant?,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class Job(
    val id: Long,
    val type: String, // scan | scrape | organize | pipeline
    val status: String, // running | completed | failed | cancelled
    val params: String?,
    val totalItems: Int,
    val doneItems: Int,
    val failItems: Int,
    val errorDetail: String?,
    val startedAt: Instant,
    val finishedAt: Instant?,
)
