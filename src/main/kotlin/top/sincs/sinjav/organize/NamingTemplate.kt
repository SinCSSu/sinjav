package top.sincs.sinjav.organize

import org.springframework.stereotype.Component
import top.sincs.sinjav.domain.Movie

/**
 * 命名模板引擎：{number} {title} {date} {maker} {series} {actress}
 * 自动清洗文件名/路径非法字符（Windows 保留字符与保留名）。
 */
@Component
class NamingTemplate {

    private val illegalChars = Regex("""[\\/:*?"<>|\u0000-\u001f]""")

    fun render(template: String, movie: Movie, actresses: List<String> = emptyList()): String {
        val date = movie.releaseDate?.toString() ?: ""
        val out = template
            .replace("{number}", movie.number)
            .replace("{title}", movie.title ?: movie.number)
            .replace("{date}", date)
            .replace("{maker}", movie.maker ?: "")
            .replace("{series}", movie.series ?: "")
            .replace("{actress}", actresses.firstOrNull() ?: "")
        return sanitizePath(out)
    }

    /** 清洗路径每一段的非法字符，保留目录分隔符 */
    fun sanitizePath(path: String): String =
        path.split('/', '\\').joinToString("/") { seg ->
            if (seg.isBlank()) "" else illegalChars.replace(seg.trim(), "_").trim().trimEnd('.', ' ')
        }.split('/').filter { it.isNotBlank() }.joinToString("/")

    fun sanitizeFileName(name: String): String {
        var s = illegalChars.replace(name, "_").trim().trimEnd('.', ' ')
        if (s.uppercase() in RESERVED_NAMES) s = "_$s"
        return s.ifBlank { "unnamed" }
    }

    companion object {
        private val RESERVED_NAMES = setOf(
            "CON", "PRN", "AUX", "NUL",
            "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
            "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9",
        )
    }
}
