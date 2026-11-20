package top.sincs.sinjav.scraper

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import top.sincs.sinjav.config.AppProperties
import java.io.File
import java.net.InetSocketAddress
import java.net.ProxySelector
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.time.Duration
import java.util.concurrent.locks.ReentrantReadWriteLock
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * 统一 HTTP 客户端：代理 / UA / Cookie / 超时 / 全局限速（并发 1~N + 请求间隔）。
 * 站点有风控，全局节流避免被封。
 *
 * 代理分流：
 * - 请求 source 命中 cfProxySources 且配置了 cfProxyUrl → 走 CF 过盾代理（如 trawl MITM）
 * - 否则走全局 proxyHost/proxyPort（未配则直连）
 */
@Component
class HttpClientFactory(private val props: AppProperties) {

    private val log = LoggerFactory.getLogger(HttpClientFactory::class.java)

    // HttpClient 随代理配置重建：SettingsService 运行时改代理后立即生效，无需重启
    @Volatile
    private var cachedClient: HttpClient? = null

    @Volatile
    private var cachedProxyKey: Pair<String, Int>? = null

    // CF 过盾代理 client（独立缓存，key = 过盾代理地址 + CA 证书路径）
    @Volatile
    private var cachedCfClient: HttpClient? = null

    @Volatile
    private var cachedCfKey: Pair<String, String>? = null

    /** source 命中过盾数据源且配置了过盾代理 */
    private fun useCfProxy(source: String?): Boolean {
        if (source.isNullOrBlank()) return false
        if (props.scraper.cfProxyUrl.isBlank()) return false
        return source.trim().lowercase() in props.cfProxySourceSet()
    }

    private fun client(source: String? = null): HttpClient =
        if (useCfProxy(source)) cfClient() else defaultClient()

    /** 请求超时：过盾链路单独用 cfTimeoutMs（需等待 CF 挑战，更长） */
    private fun timeoutFor(source: String?): Duration =
        Duration.ofMillis(if (useCfProxy(source)) props.scraper.cfTimeoutMs else props.scraper.timeoutMs)

    private fun defaultClient(): HttpClient {
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

    private fun cfClient(): HttpClient {
        val key = props.scraper.cfProxyUrl to props.scraper.cfCaCertPath
        cachedCfClient?.takeIf { cachedCfKey == key }?.let { return it }
        synchronized(this) {
            cachedCfClient?.takeIf { cachedCfKey == key }?.let { return it }
            val uri = URI.create(props.scraper.cfProxyUrl.trim())
            val port = if (uri.port > 0) uri.port else 80
            val builder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(props.scraper.cfTimeoutMs))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .proxy(ProxySelector.of(InetSocketAddress(uri.host, port)))
            sslContextWithCa()?.let { builder.sslContext(it) }
            val created = builder.build()
            cachedCfClient = created
            cachedCfKey = key
            log.info("CF 过盾代理 client 已构建: {} (CA: {})", props.scraper.cfProxyUrl, props.scraper.cfCaCertPath.ifBlank { "默认信任库" })
            return created
        }
    }

    /**
     * 构造 CF 过盾代理专用 SSLContext：
     * - MITM 签发的证书（issuer = 配置的 CA）→ 用「签名验证 + 有效期」信任。
     *   不走 JDK PKIX 严格链校验：部分 MITM 代理签发子证书时 issuer DN 的 ASN.1 编码
     *   （字符串类型/顺序）与 CA subject 不完全一致，JDK 按字节比较 DN 会判不信任链
     *   （「Path does not chain with any of the trust anchors」），而 openssl 规范化比较可通过。
     * - 其余证书（代理直通的真实站点）→ 回退 JDK 标准 PKIX（默认 cacerts + CA 合并信任库）。
     * 路径未配置或加载失败时返回 null（退回默认信任库）。
     * 支持两种路径：classpath:ca.crt（打进 jar 的资源）或文件系统路径（如挂载 trawl_ca 卷）。
     */
    private fun sslContextWithCa(): SSLContext? {
        val path = props.scraper.cfCaCertPath
        if (path.isBlank()) return null
        val certStream = openCertStream(path) ?: return null
        return runCatching {
            val anchors = mutableListOf<java.security.cert.X509Certificate>()
            val cf = CertificateFactory.getInstance("X.509")
            // 证书文件可能是 PEM 链（root + intermediates）：全部加载
            certStream.use { ins ->
                cf.generateCertificates(ins).forEachIndexed { i, cert ->
                    val x509 = cert as java.security.cert.X509Certificate
                    anchors += x509
                    log.info(
                        "CF 过盾信任 CA[{}]: subject={} CA:TRUE={}",
                        i, x509.subjectX500Principal.name, x509.basicConstraints != -1,
                    )
                }
            }

            val ks = KeyStore.getInstance(KeyStore.getDefaultType())
            val cacerts = File(System.getProperty("java.home"), "lib/security/cacerts")
            ks.load(if (cacerts.isFile) cacerts.inputStream() else null, "changeit".toCharArray())
            anchors.forEachIndexed { i, cert -> ks.setCertificateEntry("cf-proxy-ca-$i", cert) }
            val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            tmf.init(ks)
            val pkix = tmf.trustManagers.filterIsInstance<javax.net.ssl.X509ExtendedTrustManager>().first()

            // 包装信任管理器：TLS 握手时把 Java 实际收到的证书链打进日志（诊断 MITM CA 对账）
            val composite = MitmTrustManager(anchors, pkix, log)
            SSLContext.getInstance("TLS").apply { init(null, arrayOf(LoggingTrustManager(composite, log)), null) }
        }.onFailure {
            log.warn("加载 CF 过盾 CA 证书失败({})，使用默认信任库: {}", it.message, path)
        }.getOrNull()
    }

    /** 打开 CA 证书输入流；不支持或不存在时返回 null 并告警 */
    private fun openCertStream(path: String): java.io.InputStream? =
        if (path.startsWith("classpath:")) {
            val res = org.springframework.core.io.ClassPathResource(path.removePrefix("classpath:").trimStart('/'))
            if (res.exists()) res.inputStream
            else null.also { log.warn("CF 过盾 CA classpath 资源不存在，使用默认信任库: {}", path) }
        } else {
            val file = File(path)
            if (file.isFile) file.inputStream()
            else null.also { log.warn("CF 过盾 CA 证书文件不存在，使用默认信任库: {}", path) }
        }

    private val lock = ReentrantReadWriteLock()
    private var lastRequestAt = 0L

    /**
     * MITM 代理专用信任管理器：
     * 证书能被配置的 CA 之一验签且在有效期内 → 信任（不做 JDK 字节级 DN 链匹配）；
     * 否则回退 JDK 标准 PKIX（覆盖代理直通的真实站点证书）。
     */
    private class MitmTrustManager(
        private val anchors: List<java.security.cert.X509Certificate>,
        private val fallback: javax.net.ssl.X509ExtendedTrustManager,
        private val log: org.slf4j.Logger,
    ) : javax.net.ssl.X509ExtendedTrustManager() {

        private fun validateByAnchors(chain: Array<java.security.cert.X509Certificate>) {
            val leaf = chain.firstOrNull() ?: throw java.security.cert.CertificateException("空证书链")
            leaf.checkValidity()
            for (a in anchors) {
                try {
                    leaf.verify(a.publicKey)
                    return
                } catch (_: Exception) { /* 换下一张 CA 验签 */ }
            }
            throw java.security.cert.CertificateException(
                "无法用信任的 MITM CA 验签: issuer=${leaf.issuerX500Principal.name}",
            )
        }

        override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) =
            fallback.checkClientTrusted(chain, authType)

        override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {
            try {
                validateByAnchors(chain)
            } catch (e: java.security.cert.CertificateException) {
                log.warn("MITM CA 验签失败，回退 JDK PKIX: {}", e.message)
                fallback.checkServerTrusted(chain, authType)
            }
        }

        override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String, socket: java.net.Socket?) =
            fallback.checkClientTrusted(chain, authType, socket)

        override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String, socket: java.net.Socket?) {
            try {
                validateByAnchors(chain)
            } catch (e: java.security.cert.CertificateException) {
                log.warn("MITM CA 验签失败，回退 JDK PKIX: {}", e.message)
                fallback.checkServerTrusted(chain, authType, socket)
            }
        }

        override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String, engine: javax.net.ssl.SSLEngine?) =
            fallback.checkClientTrusted(chain, authType, engine)

        override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String, engine: javax.net.ssl.SSLEngine?) {
            try {
                validateByAnchors(chain)
            } catch (e: java.security.cert.CertificateException) {
                log.warn("MITM CA 验签失败，回退 JDK PKIX: {}", e.message)
                fallback.checkServerTrusted(chain, authType, engine)
            }
        }

        override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = anchors.toTypedArray()
    }


    /** TLS 握手时把实际收到的证书链（subject/issuer/指纹）打进日志后委托原信任管理器 */
    private class LoggingTrustManager(
        private val delegate: javax.net.ssl.X509ExtendedTrustManager,
        private val log: org.slf4j.Logger,
    ) : javax.net.ssl.X509ExtendedTrustManager() {

        private fun dump(chain: Array<java.security.cert.X509Certificate>?, where: String) {
            if (chain == null || chain.isEmpty()) {
                log.warn("TLS({}) 收到空证书链", where)
                return
            }
            chain.forEachIndexed { i, c ->
                val fp = java.security.MessageDigest.getInstance("SHA-256").digest(c.encoded)
                log.info(
                    "TLS({}) 收到证书[{}]: subject={} issuer={} fp={} notBefore={}",
                    where, i, c.subjectX500Principal.name, c.issuerX500Principal.name,
                    java.util.HexFormat.of().formatHex(fp), c.notBefore,
                )
                if (i == 0) savePem(c, fp)
            }
        }

        /** 把收到的 leaf 证书存盘（data/tls-debug/），供 openssl 离线验证定案 */
        private fun savePem(cert: java.security.cert.X509Certificate, fp: ByteArray) {
            runCatching {
                val dir = java.nio.file.Path.of("data", "tls-debug")
                java.nio.file.Files.createDirectories(dir)
                val b64 = java.util.Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(cert.encoded)
                val pem = "-----BEGIN CERTIFICATE-----\n$b64\n-----END CERTIFICATE-----\n"
                java.nio.file.Files.writeString(dir.resolve("${java.util.HexFormat.of().formatHex(fp)}.pem"), pem)
            }
        }

        override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) =
            delegate.checkClientTrusted(chain, authType)

        override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {
            dump(chain, "server")
            delegate.checkServerTrusted(chain, authType)
        }

        override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String, socket: java.net.Socket?) =
            delegate.checkClientTrusted(chain, authType, socket)

        override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String, socket: java.net.Socket?) {
            dump(chain, "server-socket")
            delegate.checkServerTrusted(chain, authType, socket)
        }

        override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String, engine: javax.net.ssl.SSLEngine?) =
            delegate.checkClientTrusted(chain, authType, engine)

        override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String, engine: javax.net.ssl.SSLEngine?) {
            dump(chain, "server-engine")
            delegate.checkServerTrusted(chain, authType, engine)
        }

        override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = delegate.acceptedIssuers
    }

    fun get(url: String, referer: String? = null, cookie: String? = null, source: String? = null): String {
        throttle()
        try {
            val builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(timeoutFor(source))
                .header("User-Agent", props.scraper.userAgent)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
            // 未显式指定 Referer 时按 URL 源站点推导（JavBus/JavDB 图片有 Referer 防盗链）
            builder.header("Referer", referer ?: originOf(url))
            cookie?.takeIf { it.isNotBlank() }?.let { builder.header("Cookie", it) }
            val resp = client(source).send(builder.GET().build(), HttpResponse.BodyHandlers.ofString())
            if (resp.statusCode() != 200) throw ScrapeException("HTTP ${resp.statusCode()} for $url")
            return resp.body()
        } finally {
            release()
        }
    }

    fun download(url: String, referer: String? = null, source: String? = null): ByteArray =
        downloadDetailed(url, referer, source).bytes

    /** POST JSON 请求（如 DeepL 翻译 API），返回响应体 */
    fun postJson(url: String, json: String, headers: Map<String, String> = emptyMap()): String {
        throttle()
        try {
            val builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMillis(props.scraper.timeoutMs))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
            headers.forEach { (k, v) -> builder.header(k, v) }
            val resp = client().send(builder.build(), HttpResponse.BodyHandlers.ofString())
            if (resp.statusCode() != 200) throw ScrapeException("HTTP ${resp.statusCode()} for $url")
            return resp.body()
        } finally {
            release()
        }
    }

    /** 下载二进制资源（图片），返回内容与 Content-Type */
    fun downloadDetailed(url: String, referer: String? = null, source: String? = null): Downloaded {
        throttle()
        try {
            val builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(timeoutFor(source))
                .header("User-Agent", props.scraper.userAgent)
            builder.header("Referer", referer ?: originOf(url))
            val resp = client(source).send(builder.GET().build(), HttpResponse.BodyHandlers.ofByteArray())
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

    /** 全局请求最小间隔（限速防风控）。并发上限已移至影片维度（ScraperProps.concurrency），此处不再限制同时在途请求数 */
    private fun throttle() {
        val interval = props.scraper.requestIntervalMs
        if (interval <= 0) return
        val waitMs = lock.write {
            val now = System.currentTimeMillis()
            val since = now - lastRequestAt
            if (since >= interval) {
                lastRequestAt = now
                0L
            } else {
                // 排队的请求依次错开一个间隔，保证相邻请求起始时间相差 interval
                lastRequestAt += interval
                interval - since
            }
        }
        if (waitMs > 0) Thread.sleep(waitMs)
    }

    fun release() {
        // 限速已改为纯间隔制，无需释放配额；保留空实现以兼容调用
    }
}
