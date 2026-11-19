package top.sincs.sinjav.scraper

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import top.sincs.sinjav.config.AppProperties
import tools.jackson.databind.ObjectMapper
import java.security.MessageDigest

/**
 * 翻译服务：标题/简介翻译成中文。
 * 按配置自动选择：DeepL（deepl-api-key）> 百度翻译（app-id + secret）> Google 免费接口（兜底，无需 key）。
 * 任何失败仅记警告并返回 null（调用方回退原文），不阻断刮削主流程。
 */
@Component
class TranslationService(
    private val http: HttpClientFactory,
    private val props: AppProperties,
    private val mapper: ObjectMapper,
) {

    private val log = LoggerFactory.getLogger(TranslationService::class.java)

    fun providerName(): String = when {
        props.scraper.deeplApiKey.isNotBlank() -> "deepl"
        props.scraper.baiduTranslateAppId.isNotBlank() && props.scraper.baiduTranslateSecret.isNotBlank() -> "baidu"
        else -> "google"
    }

    /** 翻译为简体中文；失败返回 null（回退原文） */
    fun translateToZh(text: String): String? {
        val input = text.trim()
        if (input.isEmpty()) return null
        return try {
            // 已是中文（无日文假名）时不再调用翻译 API——繁体会被原样返回，
            // 直接用 opencc 做繁→简转换
            val result = if (isChinese(input)) com.github.houbb.opencc4j.util.ZhConverterUtil.toSimple(input)
            else when (providerName()) {
                "deepl" -> deepl(input)
                "baidu" -> baidu(input)
                else -> google(input)
            }?.let { com.github.houbb.opencc4j.util.ZhConverterUtil.toSimple(it) }
            result?.trim()?.ifBlank { null }
        } catch (e: Exception) {
            log.warn("翻译失败（{}）: {}", providerName(), e.message)
            null
        }
    }

    /** 是否为中文文本：含汉字且不含日文假名（含假名视为日文，需走翻译） */
    private fun isChinese(text: String): Boolean =
        text.contains(Regex("""[\u4e00-\u9fff]""")) && !text.contains(Regex("""[\u3040-\u30ff]"""))

    /** 已是中文的文本直接繁→简（供调用方处理未经翻译通道的繁体字段，如 javdb/missav 的中文标题） */
    fun toSimplified(text: String): String = com.github.houbb.opencc4j.util.ZhConverterUtil.toSimple(text)

    // 短标签（类别等）翻译缓存：词表高度重复（同一标签出现在大量影片），避免重复调用翻译接口
    private val labelCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** 翻译短标签为简体中文（带缓存）；失败回退原文。类别词表等小集合使用 */
    fun translateLabel(text: String): String {
        val key = text.trim()
        if (key.isEmpty()) return text
        // 缓存防膨胀：超上限后不再存储，直接计算
        if (labelCache.size > 5000) return translateToZh(key) ?: key
        return labelCache.computeIfAbsent(key) { translateToZh(it) ?: it }
    }

    // ---------- DeepL ----------
    private fun deepl(text: String): String? {
        val key = props.scraper.deeplApiKey.trim()
        // 免费版 key 以 :fx 结尾，使用 api-free 域名
        val host = if (key.endsWith(":fx")) "https://api-free.deepl.com" else "https://api.deepl.com"
        val body = mapper.writeValueAsString(mapOf("text" to listOf(text), "target_lang" to "ZH"))
        val resp = http.postJson(
            "$host/v2/translate",
            body,
            mapOf("Authorization" to "DeepL-Auth-Key $key"),
        )
        return mapper.readTree(resp).path("translations").firstOrNull()?.path("text")?.asString()
    }

    // ---------- 百度翻译 ----------
    private fun baidu(text: String): String {
        val appId = props.scraper.baiduTranslateAppId.trim()
        val secret = props.scraper.baiduTranslateSecret.trim()
        val salt = System.currentTimeMillis().toString()
        val sign = md5(appId + text + salt + secret)
        val url = "https://fanyi-api.baidu.com/api/trans/vip/translate" +
            "?q=${urlEncode(text)}&from=auto&to=zh&appid=${urlEncode(appId)}&salt=$salt&sign=${urlEncode(sign)}"
        val resp = http.get(url)
        val root = mapper.readTree(resp)
        if (root.has("error_code")) {
            throw ScrapeException("百度翻译错误 ${root.path("error_code").asString()}: ${root.path("error_msg").asString()}")
        }
        // 可能返回多段结果，拼接
        return root.path("trans_result").joinToString("") { it.path("dst").asString() }
    }

    // ---------- Google 免费接口 ----------
    private fun google(text: String): String? {
        val url = "https://translate.googleapis.com/translate_a/single" +
            "?client=gtx&sl=auto&tl=zh-CN&dt=t&q=${urlEncode(text)}"
        val resp = http.get(url)
        // 响应形如 [[["译文","原文",...],["译文2",...]],...]
        val root = mapper.readTree(resp)
        return buildString {
            root.path(0).forEach { seg ->
                val t = seg.path(0).asString("")
                if (t.isNotEmpty()) append(t)
            }
        }.ifBlank { null }
    }

    private fun md5(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun urlEncode(s: String): String = java.net.URLEncoder.encode(s, Charsets.UTF_8)
}
