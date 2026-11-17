package top.sincs.sinjav.scraper

import org.springframework.stereotype.Component
import top.sincs.sinjav.config.AppProperties
import java.net.InetSocketAddress
import java.net.ProxySelector
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * 统一 HTTP 客户端：代理 / UA / Cookie / 超时 / 全局限速（并发 1~N + 请求间隔）。
 * 站点有风控，全局节流避免被封。
 */
@Component
class HttpClientFactory(private val props: AppProperties) {

    private val client: HttpClient by lazy {
        val builder = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(props.scraper.timeoutMs))
            .followRedirects(HttpClient.Redirect.NORMAL)
        val host = props.scraper.proxyHost
        if (host.isNotBlank() && props.scraper.proxyPort > 0) {
            builder.proxy(ProxySelector.of(InetSocketAddress(host, props.scraper.proxyPort)))
        }
        builder.build()
    }

    private val lock = ReentrantReadWriteLock()
    private var lastRequestAt = 0L
    private var permitCount = 0

    fun get(url: String, referer: String? = null, cookie: String? = null): String {
        throttle()
        try {
            val builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMillis(props.scraper.timeoutMs))
                .header("User-Agent", props.scraper.userAgent)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
            referer?.let { builder.header("Referer", it) }
            cookie?.takeIf { it.isNotBlank() }?.let { builder.header("Cookie", it) }
            val resp = client.send(builder.GET().build(), HttpResponse.BodyHandlers.ofString())
            if (resp.statusCode() != 200) throw ScrapeException("HTTP ${resp.statusCode()} for $url")
            return resp.body()
        } finally {
            release()
        }
    }

    fun download(url: String, referer: String? = null): ByteArray {
        throttle()
        try {
            val builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMillis(props.scraper.timeoutMs))
                .header("User-Agent", props.scraper.userAgent)
            referer?.let { builder.header("Referer", it) }
            val resp = client.send(builder.GET().build(), HttpResponse.BodyHandlers.ofByteArray())
            if (resp.statusCode() != 200) throw ScrapeException("HTTP ${resp.statusCode()} for image $url")
            return resp.body()
        } finally {
            release()
        }
    }

    /** 全局并发限制 + 请求最小间隔 */
    private fun throttle() {
        val interval = props.scraper.requestIntervalMs
        val maxConcurrent = props.scraper.concurrency.coerceAtLeast(1)
        while (true) {
            var waitMs = 0L
            var ok = false
            lock.write {
                if (permitCount < maxConcurrent) {
                    val now = System.currentTimeMillis()
                    val since = now - lastRequestAt
                    if (since >= interval) {
                        permitCount++
                        lastRequestAt = now
                        ok = true
                    } else {
                        waitMs = interval - since
                    }
                }
            }
            if (ok) return
            Thread.sleep(waitMs.coerceAtLeast(20))
        }
    }

    fun release() {
        lock.write { permitCount = (permitCount - 1).coerceAtLeast(0) }
    }
}
