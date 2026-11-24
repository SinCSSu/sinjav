package top.sincs.sinjav.organize

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import top.sincs.sinjav.domain.FileRecordRepository
import top.sincs.sinjav.domain.MovieRepository
import java.nio.file.Files
import java.nio.file.Path

/**
 * 上下集（`番号-A` / `番号-B`）整理行为的端到端验证：
 *
 * - 番号识别把 `SSIS-001-A` / `SSIS-001-B` 归一为同一个 `SSIS-001`（共用一份元数据）；
 * - `{part}` 让两个文件落到**同目录不同文件名**（`-cd1` / `-cd2`），不会互相跳过或覆盖；
 * - 单文件影片渲染时 `{part}` 连同前导分隔符一起消失，路径与改造前完全一致。
 *
 * 关闭 nfo 与图片下载（`generateNfo=false` / `downloadImages=false`），只验证文件落位。
 */
@SpringBootTest
class OrganizerPartIntegrationTest {

    companion object {
        /** SQLite 库文件放 build/ 下（连接池持有文件锁，@TempDir 会在上下文关闭前删除而失败） */
        private val dbFile: Path = Path.of("build", "sinjav-organize-it.db")

        @JvmStatic
        @DynamicPropertySource
        fun props(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { "jdbc:sqlite:$dbFile" }
            registry.add("sinjav.scraper.auto-translate") { "false" }
            registry.add("sinjav.images.download") { "false" }
        }
    }

    @Autowired
    private lateinit var organizer: Organizer

    @Autowired
    private lateinit var fileRecords: FileRecordRepository

    @Autowired
    private lateinit var movies: MovieRepository

    @Autowired
    private lateinit var jdbc: NamedParameterJdbcTemplate

    @TempDir
    lateinit var work: Path

    private lateinit var sourceDir: Path
    private lateinit var targetDir: Path

    @BeforeEach
    fun setUp() {
        jdbc.update("DELETE FROM file_records", emptyMap<String, Any>())
        jdbc.update("DELETE FROM movie_actresses", emptyMap<String, Any>())
        jdbc.update("DELETE FROM movie_genres", emptyMap<String, Any>())
        jdbc.update("DELETE FROM movies", emptyMap<String, Any>())

        sourceDir = Files.createDirectories(work.resolve("src"))
        targetDir = Files.createDirectories(work.resolve("dst"))
    }

    @Test
    fun `上下集整理到同目录的不同文件名`() {
        markMovieScraped("SSIS-001", "测试标题")
        val a = registerSource("SSIS-001-A.mp4", "SSIS-001")
        val b = registerSource("SSIS-001-B.mp4", "SSIS-001")

        val params = params(namingTemplate = "{number}/{number}-{part}")
        assertTrue(organizer.executeOne(a, params).ok, "A 集整理失败")
        assertTrue(organizer.executeOne(b, params).ok, "B 集整理失败")

        val cd1 = targetDir.resolve("SSIS-001/SSIS-001-cd1.mp4")
        val cd2 = targetDir.resolve("SSIS-001/SSIS-001-cd2.mp4")
        assertTrue(Files.exists(cd1), "缺少 cd1: $cd1")
        assertTrue(Files.exists(cd2), "缺少 cd2: $cd2")

        // 两条记录各自回写了自己的目标路径，没有共用同一个文件
        val targetA = fileRecords.findById(a.id)!!.targetPath
        val targetB = fileRecords.findById(b.id)!!.targetPath
        assertEquals(cd1.toString(), targetA)
        assertEquals(cd2.toString(), targetB)
    }

    @Test
    fun `标题模板加 part 时上下集也能区分`() {
        markMovieScraped("SSIS-002", "标题二")
        val a = registerSource("SSIS-002-cd1.mp4", "SSIS-002")
        val b = registerSource("SSIS-002-cd2.mp4", "SSIS-002")

        val params = params(namingTemplate = "{number}/{number} - {title}-{part}")
        assertTrue(organizer.executeOne(a, params).ok)
        assertTrue(organizer.executeOne(b, params).ok)

        assertTrue(Files.exists(targetDir.resolve("SSIS-002/SSIS-002 - 标题二-cd1.mp4")))
        assertTrue(Files.exists(targetDir.resolve("SSIS-002/SSIS-002 - 标题二-cd2.mp4")))
    }

    @Test
    fun `单文件影片的路径不受 part 与 version 影响`() {
        markMovieScraped("SSIS-003", "标题三")
        val single = registerSource("SSIS-003.mp4", "SSIS-003")

        // 默认模板带 {part}{version}，无对应标识时应完全等同 {number}/{number}
        val params = params(namingTemplate = "{number}/{number}-{part}{version}")
        assertTrue(organizer.executeOne(single, params).ok)

        val expected = targetDir.resolve("SSIS-003/SSIS-003.mp4")
        assertTrue(Files.exists(expected), "缺少 $expected")
        assertFalse(Files.exists(targetDir.resolve("SSIS-003/SSIS-003-.mp4")), "不应残留多余分隔符")
    }

    @Test
    fun `同番号多分辨率整理到同目录的不同文件名`() {
        markMovieScraped("SSIS-004", "标题四")
        val hd = registerSource("SSIS-004-1080p.mp4", "SSIS-004")
        val uhd = registerSource("SSIS-004-4K.mp4", "SSIS-004")

        val params = params(namingTemplate = "{number}/{number} - {title}{version}")
        assertTrue(organizer.executeOne(hd, params).ok, "1080p 整理失败")
        assertTrue(organizer.executeOne(uhd, params).ok, "4K 整理失败")

        assertTrue(Files.exists(targetDir.resolve("SSIS-004/SSIS-004 - 标题四 - 1080p.mp4")))
        assertTrue(Files.exists(targetDir.resolve("SSIS-004/SSIS-004 - 标题四 - 4K.mp4")))
    }

    @Test
    fun `预览对同目标路径标红报错`() {
        markMovieScraped("SSIS-005", "标题五")
        val hd = registerSource("SSIS-005-1080p.mp4", "SSIS-005")
        val uhd = registerSource("SSIS-005-4K.mp4", "SSIS-005")

        // 模板不含 {version}：两个文件必然撞同一路径，预览应直接标红而不是静默放过
        val res = organizer.preview(listOf(hd.id, uhd.id), params(namingTemplate = "{number}/{number}"))
        assertEquals(2, res.items.size)
        res.items.forEach {
            assertNotNull(it.error, "预览未标红: ${it.fileName}")
            assertTrue(it.error!!.contains("重复"), "错误信息不含「重复」: ${it.error}")
        }
    }

    @Test
    fun `模板带 version 时预览不再报重复`() {
        markMovieScraped("SSIS-006", "标题六")
        val hd = registerSource("SSIS-006-1080p.mp4", "SSIS-006")
        val uhd = registerSource("SSIS-006-4K.mp4", "SSIS-006")

        val res = organizer.preview(listOf(hd.id, uhd.id), params(namingTemplate = "{number}/{number}{version}"))
        res.items.forEach { assertNull(it.error, "不应报错: ${it.error}") }
        assertEquals(
            setOf(
                targetDir.resolve("SSIS-006/SSIS-006 - 1080p.mp4").toString(),
                targetDir.resolve("SSIS-006/SSIS-006 - 4K.mp4").toString(),
            ),
            res.items.map { it.targetPath }.toSet(),
        )
    }

    // ---------- 辅助 ----------

    private fun params(namingTemplate: String) = OrganizeParams(
        targetDir = targetDir.toString(),
        mode = "copy",
        namingTemplate = namingTemplate,
        conflictPolicy = "skip",
        generateNfo = false,
        downloadImages = false,
    )

    /** 建源文件 + file_records，返回落库后的记录 */
    private fun registerSource(fileName: String, number: String) = run {
        val path = sourceDir.resolve(fileName)
        Files.write(path, byteArrayOf(1, 2, 3))
        val record = fileRecords.upsertOnScan(path.toString(), fileName, 3L, null)
        fileRecords.updateRecognize(record.id, number, "recognized")
        fileRecords.findById(record.id)!!
    }

    private fun markMovieScraped(number: String, title: String) {
        movies.ensurePending(number, number)
        jdbc.update(
            "UPDATE movies SET status = 'scraped', title = :t, title_orig = :t WHERE number = :n",
            mapOf("t" to title, "n" to number),
        )
        assertNotNull(movies.findByNumber(number))
    }
}
