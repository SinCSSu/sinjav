package top.sincs.sinjav.api

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import top.sincs.sinjav.config.AppProperties
import top.sincs.sinjav.scraper.HttpClientFactory
import java.net.URI

/**
 * 图片代理：JavBus/JavDB 图片有 Referer 防盗链，浏览器无法携带其站点 Referer，
 * 由后端携带正确 Referer 拉取并转发。仅允许白名单站点域名（防 SSRF）。
 */
@RestController
@RequestMapping("/api/v1")
class ImagesController(
    private val http: HttpClientFactory,
    private val props: AppProperties,
    private val allowedHostSuffixes: Set<String> = DEFAULT_ALLOWED_HOSTS,
) {

    @GetMapping("/images")
    fun image(@RequestParam url: String): ResponseEntity<ByteArray> {
        val uri = runCatching { URI(url) }.getOrNull()
        val host = uri?.host?.lowercase()
        if (uri == null || host == null || (uri.scheme != "http" && uri.scheme != "https")) {
            return ResponseEntity.badRequest().build()
        }
        val allowed = allowedHostSuffixes.any { host == it || host.endsWith(".$it") }
        if (!allowed) return ResponseEntity.badRequest().build()

        return try {
            val downloaded = http.downloadDetailed(url)
            val contentType = downloaded.contentType?.substringBefore(';')?.trim()
                ?.takeIf { it.startsWith("image/") } ?: "application/octet-stream"
            ResponseEntity.ok()
                .header("Content-Type", contentType)
                .header("Cache-Control", "public, max-age=86400")
                .body(downloaded.bytes)
        } catch (e: Exception) {
            ResponseEntity.status(HttpStatus.BAD_GATEWAY).build()
        }
    }

    companion object {
        /** JavBus / JavDB 及其图床域名（javbus 剧照托管于 pics.dmm.co.jp） */
        val DEFAULT_ALLOWED_HOSTS = setOf("javbus.com", "javdb.com", "jdbstatic.com", "dmm.co.jp")
    }
}
