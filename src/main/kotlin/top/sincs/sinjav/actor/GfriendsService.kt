package top.sincs.sinjav.actor

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import top.sincs.sinjav.common.AppMetadataRepository
import top.sincs.sinjav.config.AppProperties
import top.sincs.sinjav.scraper.HttpClientFactory
import top.sincs.sinjav.scraper.ImageStore
import java.io.IOException
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant

/**
 * Gfriends 头像库（github.com/gfriends/gfriends）服务：
 * 索引同步（Filetree.json → gfriends_index 表）、按演员名查头像 URL、下载头像并做 valid_pic 校验。
 *
 * Filetree.json 有两种格式，解析层都支持：
 * - 现行（嵌套）：`{"Content": {"{上传者}": {"{演员名}.jpg": "{仓库内相对文件名}?t=..."}}}`，
 *   值可能是与键不同的文件名（如 `黒サリ.jpg` → `AI-Fix-黒川さりな.jpg`），因此 URL 用值构造；
 * - 历史（扁平）：`{"Content/{上传者}/{演员名}.jpg": "https://raw.githubusercontent.com/..."}`。
 *
 * 同名条目可能出现在多个上传者目录中（约 1.6 万条重名）。仓库目录名带 `z-`…`0-` 排序前缀，
 * JSON 出现顺序即其优先级，故按「先出现者优先」去重。
 */
@Service
class GfriendsService(
    private val http: HttpClientFactory,
    private val props: AppProperties,
    private val imageStore: ImageStore,
    private val index: GfriendsIndexRepository,
    private val metadata: AppMetadataRepository,
) {

    private val log = LoggerFactory.getLogger(GfriendsService::class.java)

    private val mapper = tools.jackson.databind.ObjectMapper()

    init {
        // 代理取 scraper.proxy-host/port（设置页的覆盖值在启动后由 SettingsService 写入，故此处不打印）
        log.info(
            "Gfriends 配置: indexUrl={} syncThresholdDays={} enableProxy={}",
            indexUrl(), props.gfriends.syncThresholdDays, props.gfriends.enableProxy,
        )
    }

    // ---------------------------------------------------------------- 索引同步

    /**
     * 同步 Filetree.json 索引（幂等，可并发调用——内部串行化）。
     *
     * 是否真的下载由 [force] 与 [AppProperties.gfriends.syncThresholdDays] 决定：
     * 索引为空（首次）、从未记录过同步时间、或距上次同步已超阈值时才下载。
     *
     * @return true = 索引可用（含"仍新鲜故跳过下载"）；false = 本次下载/解析失败但旧索引仍可用
     * @throws IllegalStateException 首次同步（索引为空）时下载失败——此时没有任何可用索引，必须让调用方感知
     */
    @Synchronized
    fun syncIndex(force: Boolean = false): Boolean {
        val existing = index.count()
        val firstSync = existing == 0L
        val lastSync = lastSyncAt()
        if (!force && !isStale(firstSync, lastSync)) {
            log.debug("Gfriends 索引仍新鲜，跳过同步（{} 条，最后同步 {}）", existing, lastSync)
            return true
        }

        log.info(
            "开始同步 Gfriends 索引: {}（force={} 现有 {} 条 最后同步 {}）",
            indexUrl(), force, existing, lastSync ?: "从未",
        )

        val downloadStart = System.currentTimeMillis()
        val json = try {
            fetchText(indexUrl(), INDEX_DOWNLOAD_TIMEOUT_MS).also {
                log.info("Gfriends 索引下载完成: {} 字符，耗时 {} ms", it.length, System.currentTimeMillis() - downloadStart)
            }
        } catch (e: Exception) {
            if (firstSync) {
                throw IllegalStateException("Gfriends 索引首次同步失败：无法下载 Filetree.json（${indexUrl()}）: ${e.message}", e)
            }
            log.warn("Gfriends 索引下载失败，继续使用旧索引（{} 条）: {}", existing, e.message)
            return false
        }

        val parseStart = System.currentTimeMillis()
        val entries = try {
            parseFiletree(json)
        } catch (e: Exception) {
            log.error("Gfriends 索引解析失败，继续使用旧索引（{} 条）: {}", existing, e.message)
            return false
        }
        if (entries.isEmpty()) {
            log.warn("Gfriends 索引解析结果为空（响应 {} 字符），保留旧索引（{} 条）", json.length, existing)
            return false
        }

        // 数据库异常按设计上抛：索引写坏了比"没更新"更严重，需调用方/任务系统感知
        val upsertStart = System.currentTimeMillis()
        index.upsertAll(entries)
        val syncedAt = System.currentTimeMillis()
        metadata.putLong(META_LAST_SYNC_AT, syncedAt)
        metadata.putLong(META_ENTRY_COUNT, entries.size.toLong())
        log.info(
            "Gfriends 索引同步完成: 解析 {} 条（原 {} 条），总耗时 {} ms（解析 {} ms / 入库 {} ms），头像地址基址 {}",
            entries.size, existing, syncedAt - downloadStart,
            upsertStart - parseStart, syncedAt - upsertStart, rawBase(),
        )
        return true
    }

    /** 索引条目数（状态接口用） */
    fun indexCount(): Long = index.count()

    /** 最后同步时间（null = 从未成功同步过） */
    fun lastSyncAt(): Instant? = metadata.getLong(META_LAST_SYNC_AT)?.let { Instant.ofEpochMilli(it) }

    /** 索引是否过期：首次（无数据/无时间戳）或超过阈值天数（阈值 <= 0 视为每次都过期） */
    private fun isStale(firstSync: Boolean, lastSync: Instant?): Boolean {
        if (firstSync || lastSync == null) return true
        val days = props.gfriends.syncThresholdDays
        if (days <= 0) return true
        val ageMs = System.currentTimeMillis() - lastSync.toEpochMilli()
        return ageMs >= days * 24L * 3600_000L
    }

    /**
     * 解析 Filetree.json → 索引条目（name = 文件名含扩展名，url = 可直接下载的完整地址）。
     * 只处理 `Content` 分支（另有 `Information` 等统计信息分支），同名先出现者优先。
     */
    private fun parseFiletree(json: String): List<GfriendsIndexEntity> {
        val root: Map<String, Any?> = mapper.readValue(json, object : tools.jackson.core.type.TypeReference<Map<String, Any?>>() {})
        val base = rawBase()
        val out = LinkedHashMap<String, GfriendsIndexEntity>()
        for ((key, value) in root) {
            when {
                // 现行嵌套格式：Content / {上传者} / {演员名}.jpg → {仓库内文件名}
                value is Map<*, *> && key.trim().equals(CONTENT_DIR, ignoreCase = true) ->
                    collectEntries(CONTENT_DIR, value, base, out)
                // 历史扁平格式：Content/{上传者}/{演员名}.jpg → 完整 raw URL
                value is String -> addEntry(key, value, base, out)
                else -> Unit
            }
        }
        return out.values.toList()
    }

    /** 递归下钻（上传者目录可能还有子目录），叶子字符串即图片地址/仓库内相对路径 */
    private fun collectEntries(
        dir: String,
        node: Map<*, *>,
        base: String,
        out: MutableMap<String, GfriendsIndexEntity>,
    ) {
        for ((rawKey, rawValue) in node) {
            val key = (rawKey as? String)?.trim() ?: continue
            if (key.isEmpty()) continue
            when (rawValue) {
                is Map<*, *> -> collectEntries("$dir/$key", rawValue, base, out)
                is String -> addEntry("$dir/$key", rawValue, base, out)
                else -> Unit
            }
        }
    }

    /** 落一条索引条目：[path] 为 `Content/{上传者}/{演员名}.{jpg|png}`，[value] 为地址或仓库内相对路径 */
    private fun addEntry(path: String, value: String, base: String, out: MutableMap<String, GfriendsIndexEntity>) {
        if (!path.startsWith("$CONTENT_DIR/")) return
        val name = path.substringAfterLast('/').trim()
        if (!isPhotoFile(name)) return
        val url = absoluteUrl(value, path, base) ?: return
        // 同名多上传者：保持 Filetree 顺序（仓库目录前缀即优先级），先出现者胜
        out.putIfAbsent(name, GfriendsIndexEntity(name = name, url = url))
    }

    /** 值可能是完整 URL（历史格式）或仓库内相对路径（现行格式，可能带 `?t=` 缓存参数） */
    private fun absoluteUrl(value: String, path: String, base: String): String? {
        val raw = value.trim()
        if (raw.startsWith("http://", ignoreCase = true) || raw.startsWith("https://", ignoreCase = true)) return raw
        val relative = raw.substringBefore('?').substringBefore('#').trim().removePrefix("/")
        // 相对路径以所在目录为基准；值缺失时回退键名（键名本身即 Content/…/xxx.jpg）
        val full = if (relative.isEmpty()) path else "${path.substringBeforeLast('/', "")}/$relative"
        return base + full.split('/').filter { it.isNotEmpty() }.joinToString("/") { encodeSegment(it) }
    }

    /** 图片 raw 基址：取索引 URL 所在目录（如 https://raw.githubusercontent.com/gfriends/gfriends/master/） */
    private fun rawBase(): String = indexUrl().substringBeforeLast('/', "").trimEnd('/') + "/"

    private fun indexUrl(): String = props.gfriends.indexUrl.ifBlank { DEFAULT_INDEX_URL }

    private fun isPhotoFile(name: String): Boolean {
        val lower = name.lowercase()
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png")
    }

    /** 路径段百分号编码（中文/空格等）；`+` 不能被当成空格，故替换为 %20 */
    private fun encodeSegment(segment: String): String =
        java.net.URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20")

    // ---------------------------------------------------------------- 查询

    /**
     * 按演员名 + 别名查头像 URL（MDCx 同款规则）：
     * 先查 [actressName]，未命中再按 [aliases] 顺序逐个查；每个名字试 `{名}.jpg` 与 `{名}.png`。
     * 匹配为**精确且大小写敏感**（仓库里 `Rino.jpg` 与 `RINO.jpg` 是不同条目）。
     * @return 命中的第一个地址；全部未命中返回 null
     */
    fun findPhoto(actressName: String, aliases: List<String> = emptyList()): String? {
        val names = buildList {
            add(actressName)
            addAll(aliases)
        }.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (names.isEmpty()) return null

        // 一次 IN 查询取回全部候选键，再按名字优先级取第一个命中
        val keys = names.flatMap { listOf("$it.jpg", "$it.png") }
        val urls = index.findUrlsByNames(keys)
        val hit = keys.firstNotNullOfOrNull { key -> urls[key]?.let { key to it } } ?: run {
            log.debug("Gfriends 未命中: 候选={}", names)
            return null
        }
        log.debug("Gfriends 命中: {} → {}", hit.first, hit.second)
        return hit.second
    }

    // ---------------------------------------------------------------- 下载

    /**
     * 下载头像到 [savePath]：下载（复用 [ImageStore.downloadImage] 的下载 + valid_pic）→ 落盘 →
     * 对**落盘文件**再做一次 [ImageStore.validPic]（防写入截断）。
     * 校验不通过（HTML 错误页/占位图等）删除文件并返回 false；网络失败同样返回 false（不抛异常）。
     */
    fun downloadPhoto(url: String, savePath: Path): Boolean {
        // 走代理时复用 ImageStore 的下载 + valid_pic（同一套判定）；直连模式用本类的 client
        val bytes = if (props.gfriends.enableProxy) {
            imageStore.downloadImage(url, timeoutMs = PHOTO_DOWNLOAD_TIMEOUT_MS)
        } else {
            runCatching { fetchBytes(url, PHOTO_DOWNLOAD_TIMEOUT_MS) }
                .onFailure { log.warn("Gfriends 头像下载失败 {}: {}", url, it.message) }
                .getOrNull()
        } ?: return false
        return runCatching {
            savePath.toAbsolutePath().parent?.let { Files.createDirectories(it) }
            Files.write(savePath, bytes)
            if (imageStore.validPic(savePath)) {
                log.info("Gfriends 头像已保存 {}（{} B）← {}", savePath, bytes.size, url)
                true
            } else {
                Files.deleteIfExists(savePath)
                log.warn("Gfriends 头像校验未通过（valid_pic），已删除 {}: {}", savePath, url)
                false
            }
        }.onFailure {
            log.warn("Gfriends 头像写入失败 {}: {}", savePath, it.message)
        }.getOrDefault(false)
    }

    // ---------------------------------------------------------------- HTTP

    /** 文本下载：enableProxy 走全局代理（HttpClientFactory），否则用直连 client */
    private fun fetchText(url: String, timeoutMs: Long): String {
        if (props.gfriends.enableProxy) return http.get(url, timeoutMs = timeoutMs)
        val resp = directClient.send(request(url, timeoutMs), HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() != 200) throw IllegalStateException("HTTP ${resp.statusCode()} for $url")
        return resp.body()
    }

    /** 二进制下载：同上，代理开关一致 */
    private fun fetchBytes(url: String, timeoutMs: Long): ByteArray {
        if (props.gfriends.enableProxy) return http.download(url, timeoutMs = timeoutMs)
        val resp = directClient.send(request(url, timeoutMs), HttpResponse.BodyHandlers.ofByteArray())
        if (resp.statusCode() != 200) throw IllegalStateException("HTTP ${resp.statusCode()} for $url")
        return resp.body()
    }

    private fun request(url: String, timeoutMs: Long): HttpRequest =
        HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofMillis(timeoutMs))
            .header("User-Agent", props.scraper.userAgent)
            .GET()
            .build()

    /** 直连 client（enableProxy=false 时用）：显式 NO_PROXY，避免 JVM 级系统代理设置干扰 */
    private val directClient: HttpClient by lazy {
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(INDEX_DOWNLOAD_TIMEOUT_MS))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .proxy(object : ProxySelector() {
                override fun select(uri: URI): List<Proxy> = listOf(Proxy.NO_PROXY)
                override fun connectFailed(uri: URI, sa: SocketAddress, ioe: IOException) = Unit
            })
            .build()
    }

    private companion object {
        /** 索引 URL 缺省值（application.yaml 未配置时兜底） */
        const val DEFAULT_INDEX_URL = "https://raw.githubusercontent.com/gfriends/gfriends/master/Filetree.json"

        /** Filetree.json 顶层目录名（另一半是 Information 统计信息） */
        const val CONTENT_DIR = "Content"

        /** 索引体积数 MB 且常走代理，单独放宽超时（普通请求默认 15s 偏紧） */
        const val INDEX_DOWNLOAD_TIMEOUT_MS = 180_000L

        /** 单张头像超时 */
        const val PHOTO_DOWNLOAD_TIMEOUT_MS = 30_000L

        /** app_metadata 键：最后同步时间（epoch millis） */
        const val META_LAST_SYNC_AT = "gfriends.index.syncedAt"

        /** app_metadata 键：本次解析的条目数（状态展示用） */
        const val META_ENTRY_COUNT = "gfriends.index.count"
    }
}
