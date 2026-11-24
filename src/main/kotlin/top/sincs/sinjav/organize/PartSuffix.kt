package top.sincs.sinjav.organize

import java.util.Locale

/**
 * 多段影片（上下集）标识识别：从源文件名提取分片序号，输出 Emby/Kodi 分片后缀 `cdN`。
 *
 * 只解析**紧跟在番号之后**的尾部，避免把 `1080p` / `4K` / `CH`（中文字幕）/ `UC`（无码）等
 * 误判成分片。识别的尾部形态（大小写不敏感，分隔符可为 `-` `_` `.` 空格）：
 *
 * | 文件名 | 结果 |
 * |---|---|
 * | `SSIS-001-cd1.mp4` / `SSIS-001_part2.mp4` / `SSIS-001 pt2.mp4` | `cd1` / `cd2` |
 * | `SSIS-001-A.mp4` / `SSIS-001_b.mp4` | `cd1` / `cd2` |
 * | `SSIS-001-1.mp4` / `SSIS-001_2.mp4` | `cd1` / `cd2` |
 * | `SSIS-001 上集.mp4` / `SSIS-001-下.mp4` | `cd1` / `cd2` |
 * | `SSIS-001.mp4` / `SSIS-001-1080p.mkv` / `SSIS-001-4K.mkv` | `null` |
 *
 * 与 [top.sincs.sinjav.scan.NumberRecognizer] 的分工：番号识别负责把 `SSIS-001-A` 归一成
 * `SSIS-001`（共用一份元数据），本类负责把被丢掉的 `A` 还原成分片序号 `cd1`。
 */
object PartSuffix {

    /** 番号之后到分片标识之间的分隔符（`SSIS-001` 后可能还有 `-` `.` `_` 空格） */
    private val LEADING_SEPARATORS = Regex("""^[\s._-]*""")

    /** 显式分片：cd1 / part2 / pt1 / disc1 / disk2 */
    private val EXPLICIT = Regex("""(?i)^(?:cd|part|pt|disc|disk)[\s._-]?(\d{1,2})(?![0-9])""")

    /** 单字母：A→1、B→2（末尾不得再跟字母数字，排除 `CH` 中文字幕、`UC` 无码等） */
    private val LETTER = Regex("""^([A-Za-z])(?![A-Za-z0-9])""")

    /** 纯数字：1 → cd1、02 → cd2（末尾不得跟字母数字，排除 `1080p`、`4K`） */
    private val DIGIT = Regex("""^(\d{1,2})(?![A-Za-z0-9])""")

    /** 中文上下集：上/下/前/后（可带「篇/集/部」） */
    private val CJK = Regex("""^(上|下|前|后)(?:篇|集|部)?""")

    /**
     * 提取分片后缀。
     *
     * @param fileName 源文件名（可含扩展名）
     * @param number 已识别的规范番号（如 `SSIS-001`），用于定位尾部起点
     * @return `cd1` / `cd2` …；无分片标识或无法定位番号时返回 `null`
     */
    fun extract(fileName: String, number: String): String? {
        if (number.isBlank() || fileName.isBlank()) return null
        val stem = fileName.substringBeforeLast('.')
        // 番号在文件名中可能出现多次（目录名/前缀），取最后一处，尾部才算分片位
        val match = numberTailPattern(number).findAll(stem).lastOrNull() ?: return null
        val tail = LEADING_SEPARATORS.replace(stem.substring(match.range.last + 1), "")
        if (tail.isEmpty()) return null

        EXPLICIT.find(tail)?.let { return "cd" + it.groupValues[1].toInt() }

        CJK.find(tail)?.let {
            return when (it.groupValues[1]) {
                "上", "前" -> "cd1"
                else -> "cd2"
            }
        }

        LETTER.find(tail)?.let {
            val offset = it.groupValues[1].uppercase(Locale.ROOT)[0] - 'A' + 1
            return "cd$offset"
        }

        DIGIT.find(tail)?.let { return "cd" + it.groupValues[1].toInt() }

        return null
    }

    /**
     * 番号尾部数字 → 边界安全的正则：`SSIS-001` → `(?<![0-9])001(?![0-9])`。
     *
     * 分片后缀总是紧跟番号的数字段，故只锚定数字段就已足够，同时兼容任意前缀与分隔写法
     * （`ssis_001`、`SSIS 001`、`heyzo_hdid_1031`、`hhd800.com@SSIS-001`）。
     * 前后加数字边界，避免 `001` 命中 `0012` 之类的更长数字。
     */
    private fun numberTailPattern(number: String): Regex =
        Regex("(?<![0-9])" + Regex.escape(number.substringAfterLast('-')) + "(?![0-9])")
}
