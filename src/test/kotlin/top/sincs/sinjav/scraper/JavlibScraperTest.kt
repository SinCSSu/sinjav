package top.sincs.sinjav.scraper

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import top.sincs.sinjav.config.AppProperties
import top.sincs.sinjav.config.SourceConfig
import java.time.LocalDate

/**
 * JavLibrary 搜索列表分支：搜索未直接跳转详情页时，需按番号在列表里找到条目再进入详情。
 * 列表里的 href 是相对路径（/cn/?v=xxx），必须按 baseUri 解析成绝对地址才能发起第二次请求。
 */
class JavlibScraperTest {

    /** 搜索结果列表页：<div class="video"><a href="相对路径"><div class="id">番号</div></a></div> */
    private val searchPage = """
        <html><body>
        <div id="rightcolumn">
          <div class="video" id="javli123">
            <a href="/cn/?v=javli123">
              <div class="id">HMN-607</div>
              <img src="/pics/cover.jpg">
            </a>
          </div>
        </div>
        </body></html>
    """.trimIndent()

    private val detailPage = """
        <html><body>
        <div id="video_info">
          <table id="video_date"><tr><td class="text">2024-09-13</td></tr></table>
          <table id="video_length"><tr><td class="text">124</td></tr></table>
          <span class="maker"><a href="#">本中</a></span>
          <span class="genre"><a href="#">中出</a></span>
        </div>
        <div id="rightcolumn"><h3><a href="#">HMN-607 テストタイトル</a></h3></div>
        <img id="video_jacket_img" src="/pics/cover.jpg">
        </body></html>
    """.trimIndent()

    private fun startServer(): com.sun.net.httpserver.HttpServer {
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            val body = if (ex.requestURI.path.startsWith("/cn/vl_searchbyid.php")) searchPage else detailPage
            val bytes = body.toByteArray(Charsets.UTF_8)
            ex.responseHeaders.add("Content-Type", "text/html; charset=UTF-8")
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()
        return server
    }

    @Test
    fun `搜索列表分支：相对链接能解析成绝对地址并进入详情页`() {
        val server = startServer()
        try {
            val base = "http://127.0.0.1:${server.address.port}"
            val props = AppProperties().apply {
                scraper.requestIntervalMs = 0
                scraper.timeoutMs = 5000
                scraper.sources["javlib"] = SourceConfig(baseUrl = base)
            }
            val meta = JavlibScraper(HttpClientFactory(props), props).scrape("HMN-607")

            assertEquals("テストタイトル", meta.title)
            assertEquals(LocalDate.parse("2024-09-13"), meta.releaseDate)
            assertEquals(124, meta.durationMin)
            assertEquals("本中", meta.maker)
            assertTrue(
                meta.coverUrl == "$base/pics/cover.jpg",
                "封面应为绝对地址，实际=${meta.coverUrl}",
            )
        } finally {
            server.stop(0)
        }
    }
}
