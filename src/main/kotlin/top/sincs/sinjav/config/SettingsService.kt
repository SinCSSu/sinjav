package top.sincs.sinjav.config

import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Service

/**
 * 运行时配置：数据库持久化 + 启动时应用覆盖值 + 更新时立即生效（直接修改 AppProperties 可变字段）。
 */
@Service
@DependsOnDatabaseInitialization
class SettingsService(
    private val jdbc: NamedParameterJdbcTemplate,
    private val props: AppProperties,
) {

    private val log = LoggerFactory.getLogger(SettingsService::class.java)

    /** 敏感配置项：日志中打码（sourceConfigs 含各源 Cookie，整体打码） */
    private val sensitiveKeys = setOf("javdbCookie", "deeplApiKey", "baiduTranslateAppId", "baiduTranslateSecret", "embyApiKey", "sourceConfigs")

    private val mapper = tools.jackson.databind.ObjectMapper()

    @PostConstruct
    fun applyFromDb() {
        val rows = jdbc.query(
            "SELECT key, value FROM settings", emptyMap<String, Any>()
        ) { rs, _ -> rs.getString("key") to rs.getString("value") }
        rows.forEach { (k, v) -> apply(k, v) }
    }

    fun update(u: top.sincs.sinjav.api.dto.SettingsUpdate) {
        val changes = buildList {
            u.sourceOrder?.let { add("sourceOrder" to it) }
            u.sourceConfigs?.let { add("sourceConfigs" to mapper.writeValueAsString(it)) }
            u.chainNormal?.let { add("chainNormal" to it) }
            u.chainFc2?.let { add("chainFc2" to it) }
            u.chainCid?.let { add("chainCid" to it) }
            u.chainGetchu?.let { add("chainGetchu" to it) }
            u.chainGyutto?.let { add("chainGyutto" to it) }
            u.userAgent?.let { add("userAgent" to it) }
            u.javdbCookie?.let { add("javdbCookie" to it) }
            u.proxyHost?.let { add("proxyHost" to it) }
            u.proxyPort?.let { add("proxyPort" to it.toString()) }
            u.cfProxyUrl?.let { add("cfProxyUrl" to it) }
            u.cfProxySources?.let { add("cfProxySources" to it) }
            u.requestIntervalMs?.let { add("requestIntervalMs" to it.toString()) }
            u.concurrency?.let { add("concurrency" to it.toString()) }
            u.timeoutMs?.let { add("timeoutMs" to it.toString()) }
            u.cfTimeoutMs?.let { add("cfTimeoutMs" to it.toString()) }
            u.namingTemplate?.let { add("namingTemplate" to it) }
            u.conflictPolicy?.let { add("conflictPolicy" to it) }
            u.generateNfo?.let { add("generateNfo" to it.toString()) }
            u.downloadImages?.let { add("downloadImages" to it.toString()) }
            u.deeplApiKey?.let { add("deeplApiKey" to it) }
            u.baiduTranslateAppId?.let { add("baiduTranslateAppId" to it) }
            u.baiduTranslateSecret?.let { add("baiduTranslateSecret" to it) }
            u.autoTranslate?.let { add("autoTranslate" to it.toString()) }
            u.embyBaseUrl?.let { add("embyBaseUrl" to it) }
            u.embyApiKey?.let { add("embyApiKey" to it) }
            u.embyProxyHost?.let { add("embyProxyHost" to it) }
            u.embyProxyPort?.let { add("embyProxyPort" to it.toString()) }
            u.imagesTtlDays?.let { add("imagesTtlDays" to it.toString()) }
        }
        if (changes.isNotEmpty()) {
            log.info(
                "配置更新: {}",
                changes.joinToString(", ") { (k, v) -> "$k=${if (k in sensitiveKeys) "***" else v}" },
            )
        }
        changes.forEach { (k, v) ->
            jdbc.update(
                """
                INSERT INTO settings (key, value) VALUES (:k, :v)
                ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value, updated_at = CURRENT_TIMESTAMP
                """,
                mapOf("k" to k, "v" to v),
            )
            apply(k, v)
        }
    }

    private fun apply(key: String, value: String) {
        when (key) {
            "sourceOrder" -> props.scraper.sourceOrder = value
            "sourceConfigs" -> applySourceConfigs(value)
            "chainNormal" -> props.scraper.chainNormal = value
            "chainFc2" -> props.scraper.chainFc2 = value
            "chainCid" -> props.scraper.chainCid = value
            "chainGetchu" -> props.scraper.chainGetchu = value
            "chainGyutto" -> props.scraper.chainGyutto = value
            "userAgent" -> props.scraper.userAgent = value
            "javdbCookie" -> props.scraper.javdbCookie = value
            "proxyHost" -> props.scraper.proxyHost = value
            "proxyPort" -> props.scraper.proxyPort = value.toIntOrNull() ?: 0
            "cfProxyUrl" -> props.scraper.cfProxyUrl = value
            "cfProxySources" -> props.scraper.cfProxySources = value
            "requestIntervalMs" -> props.scraper.requestIntervalMs = value.toLongOrNull() ?: 1500
            "concurrency" -> props.scraper.concurrency = value.toIntOrNull() ?: 1
            "timeoutMs" -> props.scraper.timeoutMs = value.toLongOrNull() ?: 15000
            "cfTimeoutMs" -> props.scraper.cfTimeoutMs = value.toLongOrNull() ?: 60000
            "namingTemplate" -> props.organize.namingTemplate = value
            "conflictPolicy" -> props.organize.conflictPolicy = value
            "generateNfo" -> props.organize.generateNfo = value.toBooleanStrictOrNull() ?: true
            "downloadImages" -> props.organize.downloadImages = value.toBooleanStrictOrNull() ?: true
            "deeplApiKey" -> props.scraper.deeplApiKey = value
            "baiduTranslateAppId" -> props.scraper.baiduTranslateAppId = value
            "baiduTranslateSecret" -> props.scraper.baiduTranslateSecret = value
            "autoTranslate" -> props.scraper.autoTranslate = value.toBooleanStrictOrNull() ?: true
            "embyBaseUrl" -> props.emby.baseUrl = value
            "embyApiKey" -> props.emby.apiKey = value
            "embyProxyHost" -> props.emby.proxyHost = value
            "embyProxyPort" -> props.emby.proxyPort = value.toIntOrNull() ?: 0
            "imagesTtlDays" -> props.images.ttlDays = value.toIntOrNull() ?: 3
        }
    }

    /** 数据源独立配置：JSON → Map（key 统一小写，覆盖项全空的条目剔除） */
    private fun applySourceConfigs(json: String) {
        val parsed = runCatching {
            mapper.readValue(json, object : tools.jackson.core.type.TypeReference<Map<String, SourceConfig>>() {})
        }.getOrElse {
            log.warn("sourceConfigs 解析失败，忽略: {}", it.message)
            return
        }
        props.scraper.sources = parsed.entries
            .associate { (k, v) -> k.trim().lowercase() to v }
            .filterValues { it.hasAnyOverride() }
            .toMutableMap()
    }
}
