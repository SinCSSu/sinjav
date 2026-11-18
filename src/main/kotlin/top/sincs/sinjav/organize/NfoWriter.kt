package top.sincs.sinjav.organize

import org.springframework.stereotype.Component
import top.sincs.sinjav.domain.Movie
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate

/**
 * 生成 Kodi/Jellyfin/Emby 兼容的 movie .nfo。
 */
@Component
class NfoWriter {

    fun write(targetDir: Path, movie: Movie, actresses: List<String>, genres: List<String>) {
        val xml = buildString {
            appendLine("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
            appendLine("<movie>")
            tag("title", movie.title ?: movie.number)
            // 标题已翻译为中文时，originaltitle 写入保留的原始标题
            tag("originaltitle", movie.titleOrig ?: movie.title)
            tag("plot", movie.plot)
            tag("premiered", movie.releaseDate?.toString())
            tag("releasedate", movie.releaseDate?.toString())
            tag("dateadded", movie.scrapedAt?.toString())
            tag("studio", movie.maker)
            tag("director", movie.director)
            tag("set", movie.series)
            movie.durationMin?.let {
                appendLine("  <runtime>${it}</runtime>")
                appendLine("  <durationinseconds>${it * 60}</durationinseconds>")
            }
            tag("mpaa", "XXX")
            tag("num", movie.number)
            actresses.forEach {
                appendLine("  <actor>")
                appendLine("    <name>${escape(it)}</name>")
                appendLine("    <type>Actor</type>")
                appendLine("  </actor>")
            }
            genres.forEach { tag("genre", it) }
            genres.forEach { tag("tag", it) }
            tag("uniqueid", movie.number, attrs = mapOf("default" to "true", "type" to "jav"))
            appendLine("</movie>")
        }
        val nfoFile = targetDir.resolve("movie.nfo")
        Files.createDirectories(targetDir)
        Files.writeString(nfoFile, xml)
    }

    private fun StringBuilder.tag(name: String, value: String?, attrs: Map<String, String> = emptyMap()) {
        if (value.isNullOrBlank()) return
        val attrStr = attrs.entries.joinToString(" ") { """${it.key}="${it.value}"""" }
        appendLine("  <$name${if (attrStr.isEmpty()) "" else " $attrStr"}>${escape(value)}</$name>")
    }

    private fun escape(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&apos;")
}
