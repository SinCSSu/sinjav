package top.sincs.sinjav.emby

import com.sun.net.httpserver.HttpServer
import top.sincs.sinjav.config.AppProperties
import tools.jackson.databind.ObjectMapper
import java.net.InetSocketAddress

/**
 * 测试用 Emby 桩服务（不依赖真实环境）：
 * - [routes]：path → JSON 响应体（未配置的路径返回空体）
 * - [failStatus]：path → 错误状态码（模拟 Emby 500/404）
 * - [requests]：记录全部请求（方法/路径/查询串/Content-Type/请求体），供上传断言
 *
 * 用法：`stub.route("/emby/Persons", json)` 预置响应，[client] 按 `emby.baseUrl = stub.baseUrl` 连上来。
 */
class EmbyStub(initialRoutes: Map<String, String> = emptyMap()) {

    data class Request(
        val method: String,
        val path: String,
        /** 原始查询串（未解码，便于断言 URL 编码） */
        val query: String?,
        val contentType: String?,
        val body: ByteArray,
    )

    /** 注意：不要用与构造参数同名的属性——构造参数在 init 块内会遮蔽属性，导致 [route] 预置的响应不生效 */
    private val routes = java.util.concurrent.ConcurrentHashMap(initialRoutes)

    val failStatus: MutableMap<String, Int> = java.util.concurrent.ConcurrentHashMap()

    val requests: MutableList<Request> = java.util.Collections.synchronizedList(mutableListOf<Request>())

    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

    init {
        server.createContext("/") { ex ->
            val body = ex.requestBody.readBytes()
            requests += Request(
                method = ex.requestMethod,
                path = ex.requestURI.path,
                query = ex.requestURI.rawQuery,
                contentType = ex.requestHeaders.getFirst("Content-Type"),
                body = body,
            )
            val status = failStatus[ex.requestURI.path] ?: 200
            if (status >= 400) {
                ex.sendResponseHeaders(status, -1)
                ex.close()
                return@createContext
            }
            val payload = (routes[ex.requestURI.path] ?: "").toByteArray()
            ex.sendResponseHeaders(200, payload.size.toLong())
            ex.responseBody.use { it.write(payload) }
        }
        server.start()
    }

    val baseUrl: String get() = "http://127.0.0.1:${server.address.port}"

    fun route(path: String, json: String) {
        routes[path] = json
    }

    /** 让指定 path 返回错误状态码（模拟 Emby 报错） */
    fun fail(path: String, status: Int = 500) {
        failStatus[path] = status
    }

    fun stop() = server.stop(0)

    fun requestsTo(path: String): List<Request> = synchronized(requests) { requests.filter { it.path == path } }

    fun posts(): List<Request> = synchronized(requests) { requests.filter { it.method == "POST" } }

    /** EmbyClient（同一套配置：地址指向桩、API Key 非空、超时收窄） */
    fun client(timeoutMs: Long = 3000): EmbyClient {
        val props = AppProperties().apply {
            scraper.timeoutMs = timeoutMs
            emby.baseUrl = baseUrl
            emby.apiKey = "test-key"
        }
        return EmbyClient(props, ObjectMapper())
    }
}
