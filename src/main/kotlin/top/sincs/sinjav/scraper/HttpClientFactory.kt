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

    // HttpClient 随代理配置重建：SettingsService 运行时改代理后立即生效，无需重启
    @Volatile
    private var cachedClient: HttpClient? = null

    @Volatile
    private var cachedProxyKey: Pair<String, Int>? = null

    private fun client(): HttpClient {
        val key = props.scraper.proxyHost to props.scraper.proxyPort
        cachedClient?.takeIf { cachedProxyKey == key }?.let { return it }
        synchronized(this) {
            cachedClient?.takeIf { cachedProxyKey == key }?.let { return it }
            val builder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(props.scraper.timeoutMs))
                .followRedirects(HttpClient.Redirect.NORMAL)
            val host = props.scraper.proxyHost
            if (host.isNotBlank() && props.scraper.proxyPort > 0) {
                builder.proxy(ProxySelector.of(InetSocketAddress(host, props.scraper.proxyPort)))
            }
            val created = builder.build()
            cachedClient = created
            cachedProxyKey = key
            return created
        }
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
            // 未显式指定 Referer 时按 URL 源站点推导（JavBus/JavDB 图片有 Referer 防盗链）
            builder.header("Referer", referer ?: originOf(url))
            cookie?.takeIf { it.isNotBlank() }?.let { builder.header("Cookie", it) }
            val resp = client().send(builder.GET().build(), HttpResponse.BodyHandlers.ofString())
            if (resp.statusCode() != 200) throw ScrapeException("HTTP ${resp.statusCode()} for $url")
            return resp.body()
        } finally {
            release()
        }
    }

    fun download(url: String, referer: String? = null): ByteArray = downloadDetailed(url, referer).bytes

    /** 下载二进制资源（图片），返回内容与 Content-Type */
    fun downloadDetailed(url: String, referer: String? = null): Downloaded {
        throttle()
        try {
            val builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMillis(props.scraper.timeoutMs))
                .header("User-Agent", props.scraper.userAgent)
            builder.header("Referer", referer ?: originOf(url))
            val resp = client().send(builder.GET().build(), HttpResponse.BodyHandlers.ofByteArray())
            if (resp.statusCode() != 200) throw ScrapeException("HTTP ${resp.statusCode()} for image $url")
            return Downloaded(resp.body(), resp.headers().firstValue("content-type").orElse(null))
        } finally {
            release()
        }
    }

    data class Downloaded(val bytes: ByteArray, val contentType: String?)

    /** 从 URL 推导源站点 Referer：scheme://host/ */
    private fun originOf(url: String): String {
        val uri = URI.create(url)
        return "${uri.scheme}://${uri.host}/"
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
