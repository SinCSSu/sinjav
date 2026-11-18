package top.sincs.sinjav.api

import org.springframework.core.io.InputStreamResource
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path

/**
 * 视频流预览：支持 HTTP Range 分片传输，浏览器 <video> 可边下边播、拖动进度。
 * 零侵入只读；MP4/WebM/MOV 可直接播放，MKV/AVI 等浏览器原生不支持（由前端提示）。
 */
@RestController
@RequestMapping("/api/v1")
class VideoController {

    @GetMapping("/videos")
    fun video(
        @RequestParam path: String,
        @RequestHeader(value = "Range", required = false) range: String?,
    ): ResponseEntity<InputStreamResource> {
        val file = Path.of(path)
        if (!Files.isRegularFile(file)) throw NoSuchElementException("文件不存在: $path")
        val size = Files.size(file)
        val contentType = contentTypeOf(path)

        val parsed = range?.let { parseRange(it, size) }
            ?: return fullResponse(file, size, contentType)

        val (start, end) = parsed
        val length = end - start + 1
        val stream = RandomAccessFile(file.toFile(), "r").use { raf ->
            raf.seek(start)
            val buf = ByteArray(length.toInt())
            raf.readFully(buf)
            buf.inputStream()
        }
        return ResponseEntity.status(HttpStatus.PARTIAL_CONTENT)
            .header("Accept-Ranges", "bytes")
            .header("Content-Range", "bytes $start-$end/$size")
            .header("Content-Length", length.toString())
            .contentType(MediaType.parseMediaType(contentType))
            .body(InputStreamResource(stream))
    }

    private fun fullResponse(file: Path, size: Long, contentType: String): ResponseEntity<InputStreamResource> =
        ResponseEntity.ok()
            .header("Accept-Ranges", "bytes")
            .header("Content-Length", size.toString())
            .contentType(MediaType.parseMediaType(contentType))
            .body(InputStreamResource(Files.newInputStream(file)))

    /** 解析 bytes=start-end / bytes=start- / bytes=-suffix；非法返回 null */
    private fun parseRange(range: String, size: Long): Pair<Long, Long>? {
        val m = Regex("^bytes=(\\d*)-(\\d*)$").find(range.trim()) ?: return null
        val (a, b) = m.groupValues.slice(1..2)
        return when {
            a.isEmpty() && b.isEmpty() -> null
            a.isEmpty() -> {
                val suffix = b.toLong()
                if (suffix <= 0 || suffix > size) null
                else (size - suffix) to (size - 1)
            }
            else -> {
                val start = a.toLong()
                if (start >= size) null
                else {
                    val end = if (b.isEmpty()) size - 1 else b.toLong().coerceAtMost(size - 1)
                    if (end < start) null else start to end
                }
            }
        }
    }

    private fun contentTypeOf(path: String): String {
        val ext = path.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "mp4", "m4v" -> "video/mp4"
            "webm" -> "video/webm"
            "mov" -> "video/quicktime"
            "mkv" -> "video/x-matroska"
            "avi" -> "video/x-msvideo"
            "wmv" -> "video/x-ms-wmv"
            "ts", "m2ts" -> "video/mp2t"
            "flv" -> "video/x-flv"
            else -> "application/octet-stream"
        }
    }
}
