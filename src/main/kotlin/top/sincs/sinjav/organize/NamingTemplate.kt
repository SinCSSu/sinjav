package top.sincs.sinjav.organize

import org.springframework.stereotype.Component
import top.sincs.sinjav.domain.Movie

/**
 * 命名模板引擎：{number} {title} {date} {maker} {series} {actress} {part} {version}
 * 自动清洗文件名/路径非法字符（Windows 保留字符与保留名）。
 * {title} 使用原始标题（标题已翻译为中文时用 title_orig），保存路径不使用译名。
 *
 * {part} 用于上下集/分片：渲染为 `-cd1` / `-cd2`（Emby/Kodi 分片语法，同目录同基名会自动合并为一个条目）。
 * {version} 用于同番号多版本：渲染为 ` - 1080p` / ` - 4K`（Emby 多版本语法，` - ` 之后即版本名）。
 * 两者在无标识时都连同紧邻的分隔符一起消失，因此单文件影片上的渲染结果与不用这两个占位符完全一致。
 */
@Component
class NamingTemplate {

    private val illegalChars = Regex("""[\\/:*?"<>|\u0000-\u001f]""")

    /** {part} 连同前导分隔符（- _ . 空格）一起匹配，避免无分片时留下 `标题-` 这样的尾巴 */
    private val partToken = Regex("""[-_\s]*\{part\}""")

    /** {version} 同理会吃掉前导分隔符；渲染值自带 ` - ` 前缀以满足 Emby 的版本分隔要求 */
    private val versionToken = Regex("""[-_\s]*\{version\}""")

    fun render(
        template: String,
        movie: Movie,
        actresses: List<String> = emptyList(),
        part: String? = null,
        version: String? = null,
    ): String {
        val date = movie.releaseDate?.toString() ?: ""
        val out = partToken.replace(template, part?.takeIf { it.isNotBlank() }?.let { "-$it" } ?: "")
            .let { t -> versionToken.replace(t, version?.takeIf { it.isNotBlank() }?.let { " - $it" } ?: "") }
            .replace("{number}", movie.number)
            .replace("{title}", movie.titleOrig ?: movie.title ?: movie.number)
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
