package top.sincs.sinjav.scan

import org.springframework.stereotype.Component
import java.util.Locale

data class RecognizedNumber(val normalized: String, val raw: String)

/**
 * 番号识别策略链：
 * 1. FC2（FC2-PPV-1234567）
 * 2. 有码普通番号（ABP-123 / ABCD-001 / abcd123）
 * 3. Heyzo（heyzo-1031 / heyzo_hdid_1031）
 * 4. 无码 Caribbean/1pondo/10musume 风格（081621-001 / 061521_001）
 */
@Component
class NumberRecognizer {

    fun recognize(fileName: String): RecognizedNumber? = recognizeInternal(fileName.substringBeforeLast('.')).let { n ->
        n?.let { RecognizedNumber(n, fileName.substringBeforeLast('.')) }
    }

    companion object {

        /** FC2-PPV-1234567 */
        private val FC2 = Regex("(?i)fc2[-_\\s]?(?:ppv)?[-_\\s]?(\\d{6,9})")
        private fun matchFc2(name: String): String? =
            FC2.find(name)?.let { "FC2-PPV-${it.groupValues[1]}" }

        /** ABP-123、ABCD-001；容忍前缀修饰（[XXX], 1080p 等），字幕组后缀等；多候选取最后一个。
         *  数字部分保留原始写法（含前导零）：ABF-017 不能归一化成 ABF-17，官方番号即带零补位 */
        private val STANDARD = Regex("(?i)(?:^|[^a-z0-9])([a-z]{2,6})[-_\\s]?(\\d{2,5})(?!\\d)")
        private fun matchStandard(name: String): String? {
            var last: String? = null
            for (m in STANDARD.findAll(name)) {
                val letters = m.groupValues[1].uppercase(Locale.ROOT)
                if (letters in IGNORED_TOKENS) continue
                last = "$letters-${m.groupValues[2]}"
            }
            return last
        }

        /** HEYZO-1031 / heyzo_hdid_1031 */
        private val HEYZO = Regex("(?i)heyzo(?:_hdid)?[-_\\s]?(\\d{3,4})")
        private fun matchHeyzo(name: String): String? =
            HEYZO.find(name)?.let { "HEYZO-${it.groupValues[1]}" }

        /** 081621-001 / 061521_001（Caribbean / 1pondo / 10musume 等），仅在含无码站标识时启用 */
        private val CARIB = Regex("(?i)(?:^|[^0-9])(\\d{6})[-_](\\d{2,3})(?!\\d)")
        private fun matchCaribbean(name: String): String? {
            val markers = listOf("carib", "1pon", "10mu", "tokyo", "pacifica", "muramura")
            val lower = name.lowercase(Locale.ROOT)
            if (markers.none { lower.contains(it) }) return null
            val m = CARIB.find(name) ?: return null
            return "CARIB-${m.groupValues[1]}-${m.groupValues[2]}"
        }

        private val IGNORED_TOKENS = setOf("CD", "HD", "4K", "PT", "VOL", "PART")

        private val strategies: List<(String) -> String?> = listOf(
            ::matchFc2, ::matchHeyzo, ::matchStandard, ::matchCaribbean,
        )

        private fun recognizeInternal(name: String): String? =
            strategies.firstNotNullOfOrNull { it(name) }

        /** 规范化 API 直接传入的番号字符串 */
        fun normalize(input: String): String? = recognizeInternal("$input.mp4")
    }
}
