package top.sincs.sinjav.organize

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import top.sincs.sinjav.domain.Movie
import java.nio.file.Files
import java.time.Instant
import java.time.LocalDate

class NfoWriterTest {

    private val nfo = NfoWriter()

    @Test
    fun `生成movie nfo`(@TempDir dir: java.nio.file.Path) {
        val movie = Movie(
            id = 1, number = "ABP-123", rawNumber = null, title = "A&B <Movie>",
            titleZh = null, releaseDate = LocalDate.of(2024, 5, 1), director = "D",
            maker = "M", label = null, series = "S", durationMin = 120, coverUrl = null,
            previewUrls = emptyList(), plot = null, source = "javbus", status = "scraped",
            failReason = null, scrapedAt = Instant.now(), createdAt = Instant.now(), updatedAt = Instant.now(),
        )
        nfo.write(dir, movie, listOf("Actress <X>"), listOf("Tag1", "Tag2"))
        val xml = Files.readString(dir.resolve("movie.nfo"))
        assertTrue(xml.contains("<title>A&amp;B &lt;Movie&gt;</title>"))
        assertTrue(xml.contains("<name>Actress &lt;X&gt;</name>"))
        assertTrue(xml.contains("<studio>M</studio>"))
        assertTrue(xml.contains("<premiered>2024-05-01</premiered>"))
        assertTrue(xml.contains("<genre>Tag1</genre>"))
    }
}
