package top.sincs.sinjav.scraper

import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** 各数据源共用的日期/时长解析（各站格式不一，统一在 Scraper 内归一化后再返回） */
object ScraperParsers {

    /** 尝试 ISO（yyyy-MM-dd）、yyyy/MM/dd、yyyy年MM月dd日 三种格式 */
    fun parseDate(raw: String?): LocalDate? {
        val s = raw?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val normalized = s.replace('/', '-').replace("年", "-").replace("月", "-").replace("日", "")
        return runCatching { LocalDate.parse(normalized) }.getOrNull()
            ?: runCatching { LocalDate.parse(normalized, DateTimeFormatter.ofPattern("yyyy-M-d")) }.getOrNull()
    }

    /** 从文本中提取分钟数：优先 "H:MM:SS" 换算，其次「xx分」「xx 分钟」中的数字 */
    fun minutesFrom(raw: String?): Int? {
        val s = raw ?: return null
        Regex("(\\d+):(\\d{2}):(\\d{2})").find(s)?.let { m ->
            return m.groupValues[1].toInt() * 60 + m.groupValues[2].toInt() + (m.groupValues[3].toInt() + 59) / 60
        }
        return Regex("(\\d+)").find(s)?.groupValues?.get(1)?.toIntOrNull()
    }

    fun urlEncode(s: String): String = java.net.URLEncoder.encode(s, Charsets.UTF_8)

    /** 空串归一：null / 空白 → null */
    fun blankToNull(s: String?): String? = s?.trim()?.takeIf { it.isNotBlank() }
}
