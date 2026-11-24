package top.sincs.sinjav.organize

import java.util.Locale

/**
 * 版本标记识别：从文件名提取分辨率/版本信息，供命名模板 `{version}` 渲染
 * （`SSIS-001 - 标题 - 1080p.mp4` 这种 Emby「多版本」命名）。
 *
 * 与 [PartSuffix] 的分工：
 * - [PartSuffix] 处理**上下集**（`-A`/`-cd1`/`上集`）→ `-cd1`，Emby 视为同一影片的**分片**（连续播放）；
 * - 本类处理**同番号多版本**（`1080p`/`4K`/`无码`/`中字`）→ ` - 1080p`，Emby 视为同一影片的**多版本**（可切换）。
 *
 * 识别前先把番号片段抹掉，避免 `HD-123` 这类番号字母被当成分辨率标记。
 * 多个标记按「分辨率 → 无码 → 破解 → 中字」顺序拼接（`1080p-中字`），不含空格，
 * 以免被 Emby 的 ` - ` 版本分隔符二次切分。
 */
object VersionSuffix {

    /** 1080p / 720p / 2160p（至少三位数字，避免误吃 `1p` 之类） */
    private val RESOLUTION_P = Regex("""(?i)(?<![0-9])(\d{3,4})p(?![a-z0-9])""")

    /** 4K / 8K / 2K（数字前不能是数字或字母，避免 `hhd800` 里的片段） */
    private val RESOLUTION_K = Regex("""(?i)(?<![0-9a-z])([248])k(?![a-z0-9])""")

    /** FHD / UHD（`hd` 单用太容易误伤，只认显式三字母缩写） */
    private val RESOLUTION_WORD = Regex("""(?i)(?<![a-z0-9])(fhd|uhd)(?![a-z0-9])""")

    private val UNCENSORED = Regex("""无码|無碼|uncensored""", RegexOption.IGNORE_CASE)

    private val LEAK = Regex("""破解|leak""", RegexOption.IGNORE_CASE)

    private val CHINESE_SUB = Regex("""中文字幕|中字""")

    /**
     * 提取版本标记。
     *
     * @param fileName 源文件名（可含扩展名）
     * @param number 已识别的规范番号（用于先抹掉番号片段）
     * @return 如 `1080p` / `4K` / `1080p-中字`；无标记时返回 `null`
     */
    fun extract(fileName: String, number: String): String? {
        if (fileName.isBlank()) return null
        val text = maskNumber(fileName.substringBeforeLast('.'), number)
        val found = LinkedHashSet<String>()

        RESOLUTION_P.find(text)?.let { found += it.groupValues[1] + "p" }
        RESOLUTION_K.find(text)?.let { found += it.groupValues[1] + "K" }
        RESOLUTION_WORD.findAll(text).forEach { found += it.groupValues[1].uppercase(Locale.ROOT) }
        if (UNCENSORED.containsMatchIn(text)) found += "无码"
        if (LEAK.containsMatchIn(text)) found += "破解"
        if (CHINESE_SUB.containsMatchIn(text)) found += "中字"

        return found.takeIf { it.isNotEmpty() }?.joinToString("-")
    }

    /** 把番号片段替换成空格，防止番号里的字母（`HD-123`）被当版本标记 */
    private fun maskNumber(stem: String, number: String): String {
        if (number.isBlank()) return stem
        val pattern = Regex(
            "(?i)" + number.split('-')
                .filter { it.isNotBlank() }
                .joinToString("[^A-Za-z0-9]*") { Regex.escape(it) },
        )
        return pattern.replace(stem, " ")
    }
}
