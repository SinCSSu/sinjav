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
    val organizeStatus: String, // pending | organized | failed | skipped
    val organizeError: String?,
    val createdAt: Instant,
)

data class Movie(
    val id: Long,
    val number: String,
    val rawNumber: String?,
    val title: String?,
    val titleZh: String?,
    val releaseDate: LocalDate?,
    val director: String?,
    val maker: String?,
    val label: String?,
    val series: String?,
    val durationMin: Int?,
    val coverUrl: String?,
    val previewUrls: List<String>,
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
