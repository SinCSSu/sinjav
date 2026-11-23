package top.sincs.sinjav.emby

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import top.sincs.sinjav.api.dto.EmbyActor
import top.sincs.sinjav.api.dto.EmbyItemDetailDto
import top.sincs.sinjav.api.dto.EmbyItemSummaryDto
import top.sincs.sinjav.api.dto.EmbyLibraryDto
import top.sincs.sinjav.api.dto.EmbyMetadataUpdate
import top.sincs.sinjav.api.dto.EmbyPageDto
import top.sincs.sinjav.api.dto.EmbyPerson
import top.sincs.sinjav.config.AppProperties
import top.sincs.sinjav.scraper.ImageFormats
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode
import java.net.InetSocketAddress
import java.net.ProxySelector
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** Emby 请求失败（含 HTTP 状态码），controller 据此转 404 等响应 */
class EmbyException(val status: Int, message: String) : RuntimeException(message)

/**
 * Emby Server 客户端（基础实现）：
 * - 鉴权走 X-Emby-Token 请求头（Emby 控制台「API 密钥」生成的 key）
 * - 独立代理配置（embyProxyHost/embyProxyPort，留空直连），与刮削全局代理互不影响；不走刮削限速
 * - 条目读取走 /Users/{userId}/Items/... 经典路由（管理 key 首个管理员用户），元数据回写走 /Items/{id}
 */
@Component
class EmbyClient(
    private val props: AppProperties,
    private val mapper: ObjectMapper,
) {

    private val log = LoggerFactory.getLogger(EmbyClient::class.java)

    @Volatile
    private var cachedClient: HttpClient? = null

    @Volatile
    private var cachedProxyKey: Pair<String, Int>? = null

    @Volatile
    private var cachedUserId: String? = null

    // ---------- 配置 ----------

    private fun baseUrl(): String {
        val raw = props.emby.baseUrl.trim().trimEnd('/')
        if (raw.isBlank()) throw IllegalStateException("未配置 Emby 服务地址，请在设置页填写")
        if (props.emby.apiKey.isBlank()) throw IllegalStateException("未配置 Emby API Key，请在设置页填写")
        // 用户可能把 /emby 前缀写进地址里，避免重复拼接
        return if (raw.endsWith("/emby")) raw else "$raw/emby"
    }

    /** HttpClient 随代理配置重建：设置页运行时改代理后立即生效，无需重启 */
    private fun client(): HttpClient {
        val key = props.emby.proxyHost to props.emby.proxyPort
        cachedClient?.takeIf { cachedProxyKey == key }?.let { return it }
        synchronized(this) {
            cachedClient?.takeIf { cachedProxyKey == key }?.let { return it }
            val builder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(props.scraper.timeoutMs))
            val host = props.emby.proxyHost.trim()
            if (host.isNotBlank() && props.emby.proxyPort > 0) {
                builder.proxy(ProxySelector.of(InetSocketAddress(host, props.emby.proxyPort)))
            }
            val created = builder.build()
            cachedClient = created
            cachedProxyKey = key
            return created
        }
    }

    private fun urlEncode(s: String): String = URLEncoder.encode(s, Charsets.UTF_8)

    // ---------- HTTP ----------

    private fun exchange(method: String, path: String, query: String = "", body: String? = null): HttpResponse<ByteArray> {
        val url = buildString {
            append(baseUrl())
            append(path)
            if (query.isNotBlank()) append('?').append(query)
        }
        val builder = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofMillis(props.scraper.timeoutMs))
            .header("X-Emby-Token", props.emby.apiKey.trim())
        if (body != null) {
            builder.header("Content-Type", "application/json")
            builder.method(method, HttpRequest.BodyPublishers.ofString(body))
        } else {
            builder.method(method, HttpRequest.BodyPublishers.noBody())
        }
        val resp = client().send(builder.build(), HttpResponse.BodyHandlers.ofByteArray())
        if (resp.statusCode() >= 400) {
            throw EmbyException(resp.statusCode(), "Emby 请求失败: HTTP ${resp.statusCode()} $path")
        }
        return resp
    }

    private fun getJson(path: String, query: String = ""): JsonNode {
        val resp = exchange("GET", path, query)
        return mapper.readTree(String(resp.body(), Charsets.UTF_8))
    }

    /**
     * 图片上传请求：Emby 的图片上传接口要求请求体为图片字节的 **Base-64 编码字符串**
     * （与 Jellyfin 的原始二进制不同），Content-Type 为图片类型。
     */
    private fun exchangeImage(method: String, path: String, contentType: String, body: ByteArray): HttpResponse<ByteArray> {
        val url = buildString { append(baseUrl()); append(path) }
        val payload = java.util.Base64.getEncoder().encodeToString(body)
        val builder = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofMillis(props.scraper.timeoutMs))
            .header("X-Emby-Token", props.emby.apiKey.trim())
            .header("Content-Type", contentType)
            .method(method, HttpRequest.BodyPublishers.ofString(payload))
        val resp = client().send(builder.build(), HttpResponse.BodyHandlers.ofByteArray())
        if (resp.statusCode() >= 400) {
            // 带上 Emby 的错误响应体（如「image is not supported」），便于定位 500 的真实原因
            val detail = String(resp.body(), Charsets.UTF_8).take(300).trim()
            throw EmbyException(resp.statusCode(), "Emby 请求失败: HTTP ${resp.statusCode()} $path" + detail.ifBlank { "" })
        }
        return resp
    }

    // ---------- 用户 ----------

    /** 管理 key 可列出用户；取首个管理员（无则第一个），缓存复用 */
    private fun userId(): String {
        cachedUserId?.let { return it }
        val root = getJson("/Users")
        val node = root.firstOrNull { it.path("IsAdministrator").asBoolean(false) }
            ?: root.firstOrNull()
            ?: throw IllegalStateException("Emby 未返回任何用户，请检查 API Key 权限")
        val id = node.path("Id").asString("")
        if (id.isBlank()) throw IllegalStateException("Emby 用户 Id 为空，请检查 API Key 权限")
        cachedUserId = id
        return id
    }

    // ---------- 媒体库 / 条目 ----------

    /** 服务器信息（测试连接用） */
    fun serverInfo(): JsonNode = getJson("/System/Info")

    /** Emby 是否已配置（服务地址 + API Key 均非空） */
    fun isConfigured(): Boolean =
        props.emby.baseUrl.trim().isNotBlank() && props.emby.apiKey.trim().isNotBlank()

    /** 触发 Emby 全媒体库扫描（整理完成后调用；未配置时抛异常，调用方自行决定是否忽略） */
    fun refreshLibrary() {
        val resp = exchange("POST", "/Library/Refresh")
        if (resp.statusCode() >= 400) {
            throw IllegalStateException("Emby 媒体库刷新失败: HTTP ${resp.statusCode()}")
        }
        log.info("已触发 Emby 媒体库刷新")
    }

    /** 应用只处理 JAV（电影），媒体库列表过滤掉剧集/音乐等无关类型 */
    private val relevantCollectionTypes = setOf("movies", "homevideos", "mixed", "")

    /** 媒体库列表（/Library/VirtualFolders，ItemId 为该库根节点 Id） */
    fun listLibraries(): List<EmbyLibraryDto> =
        getJson("/Library/VirtualFolders")
            .mapNotNull { node ->
                val id = node.path("ItemId").asString("")
                val type = node.path("CollectionType").asString("").trim().lowercase()
                if (id.isBlank() || type !in relevantCollectionTypes) {
                    null
                } else {
                    EmbyLibraryDto(
                        id = id,
                        name = node.path("Name").asString(""),
                        collectionType = type.ifBlank { null },
                    )
                }
            }

    /** 分页列出媒体库内的电影条目 */
    fun listItems(libraryId: String, startIndex: Int, limit: Int, searchTerm: String?): EmbyPageDto {
        val query = buildList {
            add("ParentId=${urlEncode(libraryId)}")
            add("IncludeItemTypes=Movie")
            add("Recursive=true")
            add("SortBy=SortName")
            add("SortOrder=Ascending")
            add("Fields=Path,ProviderIds")
            add("StartIndex=$startIndex")
            add("Limit=$limit")
            if (!searchTerm.isNullOrBlank()) add("SearchTerm=${urlEncode(searchTerm)}")
        }.joinToString("&")
        val root = getJson("/Items", query)
        val items = children(root.path("Items")).map { node -> summaryFrom(node) }
        return EmbyPageDto(page = startIndex / limit + 1, size = limit, total = root.path("TotalRecordCount").asLong(0), items = items)
    }

    // ---------- 人物（演员） ----------

    /**
     * 人物列表（GET /Persons）：演员库同步的辅助接口，用于按名字补全 Person Id。
     *
     * Emby 的响应是 `{Items:[...], TotalRecordCount:n}`，部分版本直接返回数组，两种都兼容。
     * @param searchTerm 名字模糊搜索（空 = 拉全部，注意大库体积较大）
     */
    fun listPersons(searchTerm: String? = null): List<EmbyPerson> {
        val query = buildList {
            add("Limit=$PERSON_LIMIT")
            if (!searchTerm.isNullOrBlank()) add("SearchTerm=${urlEncode(searchTerm)}")
        }.joinToString("&")
        val root = getJson("/Persons", query)
        val items = if (root.isArray) children(root) else children(root.path("Items"))
        return items.mapNotNull { node ->
            val id = node.path("Id").asString("").trim()
            val name = node.path("Name").asString("").trim()
            if (id.isBlank() || name.isBlank()) {
                null
            } else {
                EmbyPerson(
                    id = id,
                    name = name,
                    hasPrimaryImage = node.path("ImageTags").path("Primary").asString("").isNotBlank(),
                )
            }
        }
    }

    /** 条目完整元数据 */
    fun getItem(id: String): EmbyItemDetailDto {
        val node = getJson("/Users/${userId()}/Items/${urlEncode(id)}")
        return detailFrom(node)
    }

    /** 修改元数据：读取完整条目 → 覆盖非空字段 → 回写（Emby 更新接口要求提交完整条目） */
    fun updateMetadata(id: String, update: EmbyMetadataUpdate): EmbyItemDetailDto {
        val node = getJson("/Users/${userId()}/Items/${urlEncode(id)}")
        if (node is ObjectNode) {
            update.name?.let { node.put("Name", it) }
            update.originalTitle?.let { node.put("OriginalTitle", it) }
            update.overview?.let { node.put("Overview", it) }
            update.genres?.let { node.set("Genres", arrayOfNode(it)) }
            update.studios?.let { node.set("Studios", arrayOfNode(it)) }
            update.tags?.let { node.set("Tags", arrayOfNode(it)) }
            update.premiereDate?.let { node.put("PremiereDate", it) }
            update.productionYear?.let { node.put("ProductionYear", it) }
            update.officialRating?.let { node.put("OfficialRating", it) }
            update.communityRating?.let { node.put("CommunityRating", it) }
            // 回写走 /Items/{id}（官方更新接口）；旧版 /Users/{userId}/Items/{id} POST 在新 Emby 中已 404
            exchange("POST", "/Items/${urlEncode(id)}", body = mapper.writeValueAsString(node))
            log.info("Emby 元数据已更新: {} ({})", node.path("Name").asString(""), id)
        } else {
            throw IllegalStateException("Emby 返回的条目结构异常，无法更新")
        }
        return getItem(id)
    }

    /** 主图（封面）代理下载，不暴露 API Key */
    fun downloadPrimaryImage(id: String, maxWidth: Int): ImageResponse {
        val resp = exchange("GET", "/Items/${urlEncode(id)}/Images/Primary", "MaxWidth=$maxWidth")
        return ImageResponse(resp.body(), resp.headers().firstValue("content-type").orElse(null))
    }

    data class ImageResponse(val bytes: ByteArray, val contentType: String?)

    // ---------- 图片上传（重新刮削推送封面/剧照） ----------

    /**
     * 上传图片：POST /Items/{Id}/Images/{imageType}（不带索引时追加到对应类型，Backdrop 即新增一张背景图）。
     * 带索引（index >= 0）时写入指定位置。Content-Type 按字节魔数自动判定（调用方可显式覆盖）。
     */
    fun uploadImage(id: String, imageType: String, bytes: ByteArray, contentType: String? = null, index: Int? = null) {
        val ct = contentType ?: ImageFormats.detect(bytes)
            ?: throw IllegalArgumentException("无法识别图片格式，拒绝上传到 Emby（可能是损坏或未知的图片数据）")
        val path = buildString {
            append("/Items/").append(urlEncode(id)).append("/Images/").append(urlEncode(imageType))
            if (index != null) append("/").append(index)
        }
        val resp = exchangeImage("POST", path, ct, bytes)
        log.info("Emby 图片已上传 itemId={} type={} index={} contentType={} bytes={} -> HTTP {}", id, imageType, index, ct, bytes.size, resp.statusCode())
    }
    /**
     * 上传演员头像：POST /Items/{personId}/Images/Primary（Emby Person 条目主图）。
     * 协议与条目图片完全一致（Base64 字符串体 + 图片 Content-Type），直接复用 [uploadImage]。
     */
    fun uploadActorPhoto(personId: String, imageBytes: ByteArray, contentType: String? = null) {
        uploadImage(id = personId, imageType = "Primary", bytes = imageBytes, contentType = contentType)
    }

    /** 删除指定索引的图片：DELETE /Items/{Id}/Images/{imageType}/{index} */
    fun deleteImage(id: String, imageType: String, index: Int) {
        exchange("DELETE", "/Items/${urlEncode(id)}/Images/${urlEncode(imageType)}/$index")
    }

    /** 条目现有背景图数量（BackdropImageTags 数组长度） */
    fun backdropCount(id: String): Int =
        getJson("/Users/${userId()}/Items/${urlEncode(id)}").path("BackdropImageTags").size()

    // ---------- JSON 映射 ----------

    private fun arrayOfNode(values: List<String>): tools.jackson.databind.node.ArrayNode =
        mapper.createArrayNode().apply { values.forEach { add(it) } }

    /** JsonNode 子节点列表（Jackson 3 的 JsonNode.map 是成员方法，会遮蔽 Kotlin 扩展，故用显式循环） */
    private fun children(node: JsonNode): List<JsonNode> = buildList { node.forEach { add(it) } }

    private fun stringList(node: JsonNode): List<String> =
        children(node).map { it.asString("") }.filter { it.isNotBlank() }

    private fun summaryFrom(node: JsonNode): EmbyItemSummaryDto = EmbyItemSummaryDto(
        id = node.path("Id").asString(""),
        name = node.path("Name").asString(""),
        originalTitle = node.path("OriginalTitle").asString("").ifBlank { null },
        productionYear = node.path("ProductionYear").asIntOrNull(),
        premiereDate = node.path("PremiereDate").asString("").ifBlank { null },
        path = node.path("Path").asString("").ifBlank { null },
        hasImage = node.path("ImageTags").path("Primary").asString("").isNotBlank(),
    )

    private fun detailFrom(node: JsonNode): EmbyItemDetailDto = EmbyItemDetailDto(
        id = node.path("Id").asString(""),
        name = node.path("Name").asString(""),
        originalTitle = node.path("OriginalTitle").asString("").ifBlank { null },
        overview = node.path("Overview").asString("").ifBlank { null },
        genres = stringList(node.path("Genres")),
        studios = stringList(node.path("Studios")),
        tags = stringList(node.path("Tags")),
        premiereDate = node.path("PremiereDate").asString("").ifBlank { null },
        productionYear = node.path("ProductionYear").asIntOrNull(),
        officialRating = node.path("OfficialRating").asString("").ifBlank { null },
        communityRating = node.path("CommunityRating").asDoubleOrNull(),
        runtimeMin = node.path("RunTimeTicks").asLong(0).takeIf { it > 0 }?.let { (it / 600_000_000).toInt() },
        path = node.path("Path").asString("").ifBlank { null },
        providerIds = node.path("ProviderIds").properties()
            .associate { (k, v) -> k to v.asString("") }
            .filterValues { it.isNotBlank() },
        dateCreated = node.path("DateCreated").asString("").ifBlank { null },
        hasImage = node.path("ImageTags").path("Primary").asString("").isNotBlank(),
        actors = actorsFrom(node.path("People")),
    )

    /**
     * `People[]` → 演员列表。People 里混有导演/编剧等，故只保留 `Type` 为空或 `Actor` 的项
     * （Emby 的 JAV 库多数只填 Name/Id，Type 可能缺省，缺省按演员处理）。
     * `Role` 原样透传（JAV 库通常是 "Actor"，普通影片是角色名）。
     */
    private fun actorsFrom(node: JsonNode): List<EmbyActor> =
        children(node).mapNotNull { person ->
            val type = person.path("Type").asString("").trim()
            if (type.isNotEmpty() && !type.equals("Actor", ignoreCase = true)) return@mapNotNull null
            val id = person.path("Id").asString("").trim()
            val name = person.path("Name").asString("").trim()
            if (id.isEmpty() || name.isEmpty()) {
                null
            } else {
                EmbyActor(id = id, name = name, role = person.path("Role").asString("").trim().ifBlank { null })
            }
        }

    // JsonNode 数值取值（缺失/非数值时返回 null）
    private fun JsonNode.asIntOrNull(): Int? =
        if (isNumber) asInt() else null

    private fun JsonNode.asDoubleOrNull(): Double? =
        if (isNumber) asDouble() else null

    private companion object {
        /** /Persons 单次返回上限（Emby 默认分页 100 条左右，演员库需要一次拿全） */
        const val PERSON_LIMIT = 100_000
    }
}
