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
            when (providerName()) {
                "deepl" -> deepl(input)
                "baidu" -> baidu(input)
                else -> google(input)
            }?.trim()?.ifBlank { null }
        } catch (e: Exception) {
            log.warn("翻译失败（{}）: {}", providerName(), e.message)
            null
        }
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
