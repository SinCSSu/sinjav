package top.sincs.sinjav.emby

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import top.sincs.sinjav.api.dto.EmbyActor
import top.sincs.sinjav.api.dto.EmbyPerson
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.Base64
import javax.imageio.ImageIO

/**
 * EmbyClient 演员相关接口（批次 4）：`/Persons` 列表、条目 `People[]` 解析、Person 主图上传。
 *
 * 全部走本地 [EmbyStub] 桩服务，不依赖真实 Emby。
 */
class EmbyClientTest {

    private val stubs = mutableListOf<EmbyStub>()

    @AfterEach
    fun tearDown() {
        stubs.forEach { runCatching { it.stop() } }
    }

    private fun newStub(routes: Map<String, String> = emptyMap()): EmbyStub =
        EmbyStub(routes).also { stubs += it }

    private fun makeJpeg(w: Int = 120, h: Int = 180): ByteArray {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val out = ByteArrayOutputStream()
        ImageIO.write(img, "jpg", out)
        return out.toByteArray()
    }

    // ------------------------------------------------------------ /Persons

    @Test
    fun `listPersons 解析 Items 并带搜索词`() {
        val stub = newStub(
            mapOf(
                "/emby/Persons" to """
                    {
                      "Items": [
                        {"Id": "p1", "Name": "三上悠亜", "ImageTags": {"Primary": "tag-1"}},
                        {"Id": "p2", "Name": "夢乃あいか"}
                      ],
                      "TotalRecordCount": 2
                    }
                """.trimIndent(),
            ),
        )

        val persons = stub.client().listPersons("三上")

        assertEquals(
            listOf(
                EmbyPerson(id = "p1", name = "三上悠亜", hasPrimaryImage = true),
                EmbyPerson(id = "p2", name = "夢乃あいか", hasPrimaryImage = false),
            ),
            persons,
        )
        val query = stub.requestsTo("/emby/Persons").single().query.orEmpty()
        assertTrue(query.contains("SearchTerm=%E4%B8%89%E4%B8%8A"), "搜索词应 URL 编码后传给 Emby: $query")
        assertTrue(query.contains("Limit="), "应带上分页上限，避免只拿到默认 100 条: $query")
    }

    @Test
    fun `listPersons 兼容顶层数组响应并跳过缺字段条目`() {
        val stub = newStub(
            mapOf(
                "/emby/Persons" to """
                    [
                      {"Id": "p1", "Name": "三上悠亜"},
                      {"Name": "没有 Id"},
                      {"Id": "p3"}
                    ]
                """.trimIndent(),
            ),
        )

        assertEquals(listOf(EmbyPerson("p1", "三上悠亜")), stub.client().listPersons())
        assertTrue(stub.requestsTo("/emby/Persons").single().query.orEmpty().contains("Limit="))
    }

    // ------------------------------------------------------------ 条目 People[]

    @Test
    fun `getItem 把 People 解析为演员并过滤导演`() {
        val stub = newStub(
            mapOf(
                "/emby/Users" to """[{"Id": "u1", "Name": "admin", "IsAdministrator": true}]""",
                "/emby/Users/u1/Items/i1" to """
                    {
                      "Id": "i1",
                      "Name": "ABP-123 測試",
                      "People": [
                        {"Id": "p1", "Name": "三上悠亜", "Role": "Actor", "Type": "Actor"},
                        {"Id": "p9", "Name": "監督", "Role": "Director", "Type": "Director"},
                        {"Id": "p2", "Name": "夢乃あいか"},
                        {"Name": "没有 Id 的项"}
                      ]
                    }
                """.trimIndent(),
            ),
        )

        val detail = stub.client().getItem("i1")

        assertEquals(
            listOf(
                EmbyActor(id = "p1", name = "三上悠亜", role = "Actor"),
                EmbyActor(id = "p2", name = "夢乃あいか", role = null),
            ),
            detail.actors,
            "Type 为空按演员处理；Director 应被过滤；缺 Id/Name 的项跳过",
        )
    }

    @Test
    fun `getItem 无 People 字段时演员列表为空`() {
        val stub = newStub(
            mapOf(
                "/emby/Users" to """[{"Id": "u1", "IsAdministrator": true}]""",
                "/emby/Users/u1/Items/i1" to """{"Id": "i1", "Name": "ABP-123"}""",
            ),
        )

        assertTrue(stub.client().getItem("i1").actors.isEmpty())
    }

    // ------------------------------------------------------------ Person 主图上传

    @Test
    fun `uploadActorPhoto 以 Base64 提交到 Person 主图接口`() {
        val stub = newStub()
        val jpeg = makeJpeg()

        stub.client().uploadActorPhoto("person-1", jpeg)

        val post = stub.posts().single()
        assertEquals("/emby/Items/person-1/Images/Primary", post.path)
        assertEquals("image/jpeg", post.contentType, "Content-Type 按字节魔数判定")
        val decoded = Base64.getDecoder().decode(String(post.body, StandardCharsets.UTF_8))
        assertEquals(jpeg.toList(), decoded.toList(), "Emby 图片上传协议要求请求体为 Base64 字符串")
    }

    @Test
    fun `uploadActorPhoto 非图片字节在本地即被拒绝`() {
        val stub = newStub()
        val error = runCatching { stub.client().uploadActorPhoto("person-1", "<html>not an image</html>".toByteArray()) }
            .exceptionOrNull()

        assertTrue(error is IllegalArgumentException, "无法识别格式时应抛 IllegalArgumentException: $error")
        assertTrue(stub.posts().isEmpty(), "不应把非法字节提交给 Emby")
        assertNull(stub.requestsTo("/emby/Items/person-1/Images/Primary").firstOrNull())
    }
}
